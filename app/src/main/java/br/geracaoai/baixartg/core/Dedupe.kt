package br.geracaoai.baixartg.core

import android.content.Context
import br.geracaoai.baixartg.tg.Td
import br.geracaoai.baixartg.tg.TdException
import br.geracaoai.baixartg.tg.Uploader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Limpar repetidos" das Mensagens Salvas. Só lê metadados (nunca baixa arquivo).
 * Iguais = mesmo arquivo do Telegram (unique_id) OU mesmo tamanho + mesma duração (±1 s) + mesma largura/altura.
 * De cada grupo fica a cópia de MAIOR QUALIDADE (resolução, depois taxa de bits, depois tamanho; empate = a mais recente);
 * as outras são apagadas só depois da confirmação. Nunca apaga a única cópia.
 */
object Dedupe {
    /** Regra única dos dois modos: maior resolução → maior taxa de bits (tamanho/duração) → maior arquivo → mais recente. */
    private val QUALITY = compareBy<E>({ it.w.toLong() * it.h }, { if (it.dur > 0) it.size / it.dur else it.size }, { it.size }, { it.date }, { it.id })
    /** Entrada compacta (sem guardar a mensagem inteira). kind: 0 vídeo, 1 documento-vídeo, 2 foto. */
    class E(val id: Long, val date: Int, val size: Long, val dur: Int, val w: Int, val h: Int, val uid: String, val kind: Int, val cap: String,
            val thumbId: Int = 0, val miniD: Long = 0, val miniP: Long = 0, val miniOk: Boolean = false)
    /** sure = sugestão de alta confiança (só no modo por imagem). */
    class G(val keep: Long, val dels: LongArray, val sure: Boolean = false)

    /** Modo do último resultado: false = exato (mesmo arquivo), true = por imagem (miniatura + duração). */
    @Volatile var visual = false; private set
    /** Caminho local da miniatura de cada mensagem (modo por imagem). */
    @Volatile var thumbs: Map<Long, String> = emptyMap(); private set

    enum class Phase { IDLE, SCANNING, RESULT, DELETING, DONE, ERROR }
    val phase = MutableStateFlow(Phase.IDLE)
    val status = MutableStateFlow("")
    val scanned = MutableStateFlow(0)
    val estimate = MutableStateFlow(0)
    val delDone = MutableStateFlow(0)
    val delTotal = MutableStateFlow(0)
    /** Resultado: grupos + dados das mensagens dos grupos (só delas). */
    @Volatile var groups: List<G> = emptyList(); private set
    @Volatile var info: Map<Long, E> = emptyMap(); private set
    /** Mensagens desmarcadas pelo usuário (não serão apagadas). */
    val excluded = MutableStateFlow<Set<Long>>(emptySet())

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var planFile: File? = null
    private var doneFile: File? = null

    fun init(c: Context) {
        app = c.applicationContext
        planFile = File(app.filesDir, "dedupe_plan.json"); doneFile = File(app.filesDir, "dedupe_done.txt")
        loadPlan()
    }
    fun hasPendingDelete() = planFile?.exists() == true && JSONObjectOrNull(planFile!!)?.optBoolean("deleting") == true

    private fun JSONObjectOrNull(f: File) = try { JSONObject(f.readText()) } catch (_: Exception) { null }

    fun toDeleteCount(): Int { val ex = excluded.value; return groups.sumOf { g -> g.dels.count { it !in ex } } }
    fun toDeleteBytes(): Long { val ex = excluded.value; return groups.sumOf { g -> g.dels.filter { it !in ex }.sumOf { info[it]?.size ?: 0L } } }
    fun toggle(id: Long, v: Boolean) { excluded.value = if (v) excluded.value - id else excluded.value + id }
    fun setAll(v: Boolean) { excluded.value = if (v) emptySet() else groups.flatMap { it.dels.toList() }.toSet() }
    /** Modo por imagem: marca só os grupos de alta confiança (nunca automático). */
    fun allCount(): Int = groups.sumOf { it.dels.size }
    fun markSuggested() { excluded.value = groups.filter { !it.sure }.flatMap { it.dels.toList() }.toSet() }

    fun cancel() { job?.cancel(); if (phase.value == Phase.SCANNING) phase.value = Phase.IDLE }
    fun reset() { if (phase.value == Phase.DELETING) return; job?.cancel(); groups = emptyList(); info = emptyMap(); excluded.value = emptySet(); phase.value = Phase.IDLE; status.value = ""
        planFile?.delete(); doneFile?.delete() }

    // ---------------- análise ----------------
    fun analyze(includePhotos: Boolean, byImage: Boolean = false) {
        if (job?.isActive == true) return
        if (byImage) { analyzeVisual(); return }
        visual = false; thumbs = emptyMap()
        job = scope.launch {
            phase.value = Phase.SCANNING; scanned.value = 0; status.value = "Conectando ao Telegram…"
            try {
                if (!Td.awaitReady(60_000)) throw IllegalStateException("Telegram não conectado")
                val chat = Uploader.savedChat()
                estimate.value = try {
                    var n = Td.send(TdApi.GetChatMessageCount(chat, null, TdApi.SearchMessagesFilterVideo(), false)).count +
                        Td.send(TdApi.GetChatMessageCount(chat, null, TdApi.SearchMessagesFilterDocument(), false)).count
                    if (includePhotos) n += Td.send(TdApi.GetChatMessageCount(chat, null, TdApi.SearchMessagesFilterPhoto(), false)).count
                    n
                } catch (e: Exception) { if (e is CancellationException) throw e; 0 }
                val all = ArrayList<E>(maxOf(1000, estimate.value))
                var from = 0L; var empty = 0; var read = 0
                while (isActive) {
                    val r = flood { Td.send(TdApi.GetChatHistory(chat, from, 0, 100, false)) }
                    if (r.messages.isEmpty()) { if (++empty >= 3) break; delay(600); continue }
                    empty = 0
                    for (m in r.messages) { read++; entryOf(m, includePhotos)?.let { all += it } }
                    from = r.messages.last().id
                    scanned.value = all.size
                    status.value = "Analisando ${fmtN(all.size)}" + (if (estimate.value > 0) " de ~${fmtN(estimate.value)}" else "") + " • ${fmtN(read)} mensagens lidas"
                }
                status.value = "Comparando…"
                val gs = group(all)
                val keepIds = HashSet<Long>(); gs.forEach { g -> keepIds += g.keep; g.dels.forEach { keepIds += it } }
                info = all.filter { it.id in keepIds }.associateBy { it.id }
                groups = gs.sortedByDescending { g -> g.dels.size }
                excluded.value = emptySet()
                phase.value = Phase.RESULT
                status.value = if (gs.isEmpty()) "Nenhum repetido encontrado em ${fmtN(all.size)} arquivos." else "${fmtN(all.size)} arquivos analisados."
            } catch (e: CancellationException) { phase.value = Phase.IDLE; status.value = "Análise cancelada."; throw e
            } catch (e: Exception) { phase.value = Phase.ERROR; status.value = "Erro: ${e.message}" }
        }
    }

    // ---------------- modo por imagem ----------------
    private const val VD = 6        // agrupar: pHash ≤6 E dHash ≤6
    private const val VSURE = 4     // sugerido: miniatura E miniatura completa ≤4
    private class H(val d: Long, val p: Long)

    private fun hashBytes(b: ByteArray?): H? {
        if (b == null || b.isEmpty()) return null
        val bmp = try { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) } catch (_: Throwable) { null } ?: return null
        return try { if (VisualHash.usable(bmp)) H(VisualHash.dHash(bmp), VisualHash.pHash(bmp)) else null } finally { bmp.recycle() }
    }
    private fun hashFile(path: String): H? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var ss = 1; while (o.outWidth / (ss * 2) >= 96 && o.outHeight / (ss * 2) >= 96) ss *= 2
        val bmp = try { android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss }) } catch (_: Throwable) { null } ?: return null
        return try { if (VisualHash.usable(bmp)) H(VisualHash.dHash(bmp), VisualHash.pHash(bmp)) else null } finally { bmp.recycle() }
    }
    private fun near(a: H, b: H, max: Int) = VisualHash.ham(a.d, b.d) <= max && VisualHash.ham(a.p, b.p) <= max

    private fun analyzeVisual() {
        job = scope.launch {
            phase.value = Phase.SCANNING; scanned.value = 0; status.value = "Conectando ao Telegram…"
            try {
                if (!Td.awaitReady(60_000)) throw IllegalStateException("Telegram não conectado")
                val chat = Uploader.savedChat()
                estimate.value = try { Td.send(TdApi.GetChatMessageCount(chat, null, TdApi.SearchMessagesFilterVideo(), false)).count } catch (e: Exception) { if (e is CancellationException) throw e; 0 }
                val all = ArrayList<E>(maxOf(1000, estimate.value))
                var from = 0L; var empty = 0; var read = 0
                while (isActive) {
                    val r = flood { Td.send(TdApi.GetChatHistory(chat, from, 0, 100, false)) }
                    if (r.messages.isEmpty()) { if (++empty >= 3) break; delay(600); continue }
                    empty = 0
                    for (m in r.messages) {
                        read++
                        val c = m.content as? TdApi.MessageVideo ?: continue
                        val v = c.video; if (v.duration <= 0) continue
                        val mh = hashBytes(v.minithumbnail?.data)
                        all += E(m.id, m.date, fsize(v.video), v.duration, v.width, v.height, v.video?.remote?.uniqueId ?: "", 0,
                            ((c.caption?.text ?: "").replace('\n', ' ').take(70)).ifBlank { v.fileName ?: "" },
                            v.thumbnail?.file?.id ?: 0, mh?.d ?: 0, mh?.p ?: 0, mh != null)
                    }
                    from = r.messages.last().id
                    scanned.value = all.size
                    status.value = "Lendo vídeos: ${fmtN(all.size)}" + (if (estimate.value > 0) " de ~${fmtN(estimate.value)}" else "")
                }
                // só baixa miniatura de quem tem outro vídeo com a mesma duração (±1 s) e a mesma proporção
                val sorted = all.sortedBy { it.dur }
                val cand = HashSet<Long>()
                for (i in sorted.indices) {
                    var j = i + 1
                    while (j < sorted.size && sorted[j].dur - sorted[i].dur <= 1) {
                        if (VisualHash.aspectOk(sorted[i].w, sorted[i].h, sorted[j].w, sorted[j].h)) { cand += sorted[i].id; cand += sorted[j].id }
                        j++
                    }
                }
                val list = sorted.filter { it.id in cand && it.thumbId != 0 }
                val full = java.util.concurrent.ConcurrentHashMap<Long, H>()
                val paths = java.util.concurrent.ConcurrentHashMap<Long, String>()
                val done = java.util.concurrent.atomic.AtomicInteger(0)
                val gate = kotlinx.coroutines.sync.Semaphore(3)
                estimate.value = list.size; scanned.value = 0
                coroutineScope {
                    for (e in list) {
                        gate.acquire()
                        launch {
                            try {
                                val f = try { flood { Td.send(TdApi.DownloadFile(e.thumbId, 1, 0, 0, true)) } } catch (x: Exception) { if (x is CancellationException) throw x; null }
                                val p = f?.local?.path?.takeIf { f.local.isDownloadingCompleted && it.isNotBlank() }
                                if (p != null) { paths[e.id] = p; hashFile(p)?.let { full[e.id] = it } }
                            } finally {
                                gate.release()
                                val n = done.incrementAndGet(); scanned.value = n
                                status.value = "Comparando miniaturas: ${fmtN(n)} de ${fmtN(list.size)}"
                            }
                        }
                        delay(40)
                    }
                }
                status.value = "Agrupando…"
                // grupos rigorosos: cada membro bate com TODOS os outros (duração ±1 s, proporção, pHash ≤6 E dHash ≤6)
                val byId = all.associateBy { it.id }
                val ok = list.filter { full[it.id] != null }
                val used = HashSet<Long>()
                val gs = ArrayList<G>()
                for (i in ok.indices) {
                    val a = ok[i]; if (a.id in used) continue
                    val members = arrayListOf(a)
                    var j = i + 1
                    while (j < ok.size && ok[j].dur - a.dur <= 1) {
                        val b = ok[j]
                        if (b.id !in used && members.all { m -> kotlin.math.abs(m.dur - b.dur) <= 1 && VisualHash.aspectOk(m.w, m.h, b.w, b.h) && near(full[m.id]!!, full[b.id]!!, VD) }) members += b
                        j++
                    }
                    if (members.size < 2) continue
                    members.forEach { used += it.id }
                    val keep = members.maxWith(QUALITY)   // maior qualidade fica
                    val sure = members.all { m -> m === keep || (near(full[m.id]!!, full[keep.id]!!, VSURE) && m.miniOk && keep.miniOk &&
                        VisualHash.ham(m.miniD, keep.miniD) <= VSURE && VisualHash.ham(m.miniP, keep.miniP) <= VSURE) }
                    gs += G(keep.id, members.filter { it !== keep }.map { it.id }.toLongArray(), sure)
                }
                val ids = HashSet<Long>(); gs.forEach { g -> ids += g.keep; g.dels.forEach { ids += it } }
                info = ids.mapNotNull { byId[it] }.associateBy { it.id }
                thumbs = paths.filterKeys { it in ids }
                groups = gs.sortedWith(compareByDescending<G> { it.sure }.thenByDescending { it.dels.size })
                visual = true
                excluded.value = groups.flatMap { it.dels.toList() }.toSet()   // NADA marcado: você escolhe
                phase.value = Phase.RESULT
                status.value = if (gs.isEmpty()) "Nenhum repetido por imagem em ${fmtN(all.size)} vídeos." else "${fmtN(all.size)} vídeos analisados. Nada vem marcado: confira as miniaturas e marque o que for igual."
            } catch (e: CancellationException) { phase.value = Phase.IDLE; status.value = "Análise cancelada."; throw e
            } catch (e: Exception) { phase.value = Phase.ERROR; status.value = "Erro: ${e.message}" }
        }
    }

    private val VIDEO_EXT = Regex("""(?i)\.(mp4|mkv|webm|mov|m4v|avi|ts|3gp|flv|wmv)$""")
    private fun entryOf(m: TdApi.Message, photos: Boolean): E? {
        fun cap(t: TdApi.FormattedText?) = (t?.text ?: "").replace('\n', ' ').take(70)
        return when (val c = m.content) {
            is TdApi.MessageVideo -> c.video.let { v -> E(m.id, m.date, fsize(v.video), v.duration, v.width, v.height, v.video?.remote?.uniqueId ?: "", 0, cap(c.caption).ifBlank { v.fileName ?: "" }) }
            is TdApi.MessageDocument -> c.document.let { d ->
                val isVid = (d.mimeType ?: "").startsWith("video/") || VIDEO_EXT.containsMatchIn(d.fileName ?: "")
                if (!isVid) null else E(m.id, m.date, fsize(d.document), 0, 0, 0, d.document?.remote?.uniqueId ?: "", 1, cap(c.caption).ifBlank { d.fileName ?: "" })
            }
            is TdApi.MessagePhoto -> if (!photos) null else c.photo.sizes.maxByOrNull { it.width * it.height }?.let { s ->
                E(m.id, m.date, fsize(s.photo), 0, s.width, s.height, s.photo?.remote?.uniqueId ?: "", 2, cap(c.caption))
            }
            else -> null
        }
    }
    private fun fsize(f: TdApi.File?): Long = f?.let { if (it.size > 0) it.size else it.expectedSize } ?: 0L

    /** Agrupa: unique_id igual, ou tamanho igual + duração ±1 s + largura/altura iguais (quando houver). */
    private fun group(all: List<E>): List<G> {
        val n = all.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        val byUid = HashMap<String, Int>()
        for (i in 0 until n) { val u = all[i].uid; if (u.isNotBlank()) { val j = byUid.putIfAbsent(u, i); if (j != null) union(i, j) } }
        val bySize = HashMap<Long, MutableList<Int>>()
        for (i in 0 until n) if (all[i].size > 0) bySize.getOrPut(all[i].size) { ArrayList(2) } += i
        for (lst in bySize.values) if (lst.size > 1) for (x in 0 until lst.size) for (y in x + 1 until lst.size) {
            val a = all[lst[x]]; val b = all[lst[y]]
            if ((a.kind == 2) != (b.kind == 2)) continue
            if (kotlin.math.abs(a.dur - b.dur) > 1) continue
            if (a.w > 0 && b.w > 0 && (a.w != b.w || a.h != b.h)) continue
            union(lst[x], lst[y])
        }
        val comp = HashMap<Int, MutableList<E>>()
        for (i in 0 until n) comp.getOrPut(find(i)) { ArrayList(2) } += all[i]
        return comp.values.filter { it.size > 1 }.map { l ->
            val keep = l.maxWith(QUALITY)   // maior qualidade fica (cópias idênticas: a mais recente)
            G(keep.id, l.filter { it !== keep }.map { it.id }.toLongArray())
        }
    }

    // ---------------- apagar (em segundo plano, retomável) ----------------
    /** Grava o plano (só o que ficou marcado) e começa a apagar no serviço em primeiro plano. */
    fun startDelete() {
        if (phase.value == Phase.DELETING) return
        val ex = excluded.value
        val gs = groups.map { G(it.keep, it.dels.filter { d -> d !in ex }.toLongArray()) }.filter { it.dels.isNotEmpty() }
        savePlan(gs, deleting = true)
        doneFile?.delete()
        DedupeService.start(app)
    }

    private fun savePlan(gs: List<G>, deleting: Boolean) {
        try {
            val a = JSONArray()
            for (g in gs) {
                val members = listOf(g.keep) + g.dels.toList()
                a.put(JSONObject().put("k", g.keep).put("d", JSONArray(g.dels.toList()))
                    .put("dt", JSONArray(members.map { info[it]?.date ?: 0 })))
            }
            planFile?.writeText(JSONObject().put("deleting", deleting).put("groups", a).toString())
        } catch (_: Exception) {}
    }
    private class PG(val keep: Long, val dels: LongArray, val dates: Map<Long, Int>)
    private fun loadPlanGroups(): List<PG> = try {
        val o = JSONObject(planFile!!.readText()); val a = o.getJSONArray("groups")
        (0 until a.length()).map { i -> val g = a.getJSONObject(i); val d = g.getJSONArray("d"); val dt = g.optJSONArray("dt")
            val dels = LongArray(d.length()) { d.getLong(it) }
            val members = listOf(g.getLong("k")) + dels.toList()
            PG(g.getLong("k"), dels, members.mapIndexed { idx, id -> id to (dt?.optInt(idx) ?: 0) }.toMap()) }
    } catch (_: Exception) { emptyList() }
    private fun loadPlan() { if (hasPendingDelete()) { phase.value = Phase.DELETING; status.value = "Apagando repetidos (retomando)…" } }

    /** Chamado pelo serviço. Lotes de até 100; antes de cada lote confere que a cópia mantida de cada grupo ainda existe. */
    suspend fun runDelete() {
        phase.value = Phase.DELETING
        val plan = loadPlanGroups()
        val done = HashSet<Long>(); doneFile?.takeIf { it.exists() }?.forEachLine { it.trim().toLongOrNull()?.let(done::add) }
        delTotal.value = plan.sumOf { it.dels.size }; delDone.value = done.size
        try {
            if (!Td.awaitReady(120_000)) throw IllegalStateException("Telegram não conectado")
            val chat = Uploader.savedChat()
            var skipped = 0; var deleted = done.size
            val pending = plan.filter { g -> g.dels.any { it !in done } }
            var i = 0
            while (i < pending.size) {
                currentCoroutineContext().ensureActive()
                val batch = ArrayList<PG>(); var cnt = 0
                while (i < pending.size && (cnt == 0 || cnt + pending[i].dels.size <= 100)) { batch += pending[i]; cnt += pending[i].dels.size; i++ }
                // re-verifica no Telegram: quem ainda existe
                val ids = batch.flatMap { listOf(it.keep) + it.dels.toList() }.distinct()
                val exists = HashSet<Long>()
                for (chunk in ids.chunked(100)) {
                    val r = flood { Td.send(TdApi.GetMessages(chat, chunk.toLongArray())) }
                    r.messages.forEach { m -> if (m != null) exists += m.id }
                }
                val toDel = ArrayList<Long>()
                for (g in batch) {
                    val present = (listOf(g.keep) + g.dels.toList()).filter { it in exists }
                    if (present.size < 2) { skipped += g.dels.count { it !in done }; g.dels.forEach { done += it }; continue }   // nunca apaga a única cópia
                    // a mantida sumiu? mantém a mais recente que ainda existe
                    val keep = if (g.keep in exists) g.keep else present.maxWith(compareBy<Long> { g.dates[it] ?: 0 }.thenBy { it })
                    val del = g.dels.filter { it in exists && it != keep && it !in done }
                    if (keep !in exists || del.size >= present.size) { skipped += del.size; continue }
                    toDel += del
                    g.dels.filter { it !in exists }.forEach { done += it }
                }
                for (chunk in toDel.chunked(100)) {
                    flood { Td.send(TdApi.DeleteMessages(chat, chunk.toLongArray(), true)) }
                    done += chunk; deleted += chunk.size
                    try { doneFile?.appendText(chunk.joinToString("\n", postfix = "\n")) } catch (_: Exception) {}
                    delDone.value = done.size
                    status.value = "Apagando: ${fmtN(done.size)} de ${fmtN(delTotal.value)}"
                    delay(400)
                }
                batch.forEach { g -> g.dels.forEach { d -> if (d !in exists) done += d } }
                delDone.value = done.size
            }
            planFile?.delete(); doneFile?.delete()
            phase.value = Phase.DONE
            status.value = "Pronto! ${fmtN(deleted)} repetidos apagados" + (if (skipped > 0) ", ${fmtN(skipped)} pulados (a outra cópia já não existia)" else "") + ". Sempre ficou uma cópia de cada."
        } catch (e: CancellationException) { status.value = "Pausado. Continua quando abrir o app."; throw e
        } catch (e: Exception) { phase.value = Phase.ERROR; status.value = "Erro ao apagar: ${e.message}. Toque em Continuar para retomar." }
    }

    /** Repete depois de FLOOD_WAIT ("retry after N"). */
    private suspend fun <T> flood(block: suspend () -> T): T {
        var tries = 0
        while (true) {
            try { return block() } catch (e: TdException) {
                val m = Regex("""(?i)(?:retry after|FLOOD_WAIT_)\s*(\d+)""").find(e.message ?: "")
                if ((e.code == 429 || m != null) && tries < 20) {
                    val s = m?.groupValues?.get(1)?.toLongOrNull() ?: 5L
                    status.value = "Telegram pediu pausa de ${s}s…"
                    delay((s + 1) * 1000); tries++; continue
                }
                throw e
            }
        }
    }

    fun resumeDelete() { if (hasPendingDelete()) DedupeService.start(app) }
    fun fmtN(n: Int) = "%,d".format(n).replace(',', '.')
}
