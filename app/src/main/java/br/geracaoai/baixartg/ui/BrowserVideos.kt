package br.geracaoai.baixartg.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import br.geracaoai.baixartg.Prefs
import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request

/**
 * Navegador: detecta os cards de vídeo enquanto o usuário rola e resolve o vídeo real em segundo plano
 * (HTML estático → servidor yt-dlp). Só entra na lista o que tem stream de vídeo resolvido; nunca foto/capa.
 */
/** Diagnóstico do navegador (vai no "Copiar diagnóstico"; sem links). */
object BrowserDiag {
    private fun m() = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    val photoRejects = m(); val counters = m()
    @Volatile var lastRaw = 0; @Volatile var lastCands = 0; @Volatile var lastKept = 0; @Volatile var photoCalls = 0; @Volatile var photoReplies = 0; @Volatile var photoErrs = 0; @Volatile var lastPhotoErr = ""
    @Volatile var lastImgs = -1; @Volatile var collectMs = 0L; @Volatile var collectMaxMs = 0L; @Volatile var collects = 0
    fun inc(k: String, n: Int = 1) { counters.getOrPut(k) { java.util.concurrent.atomic.AtomicInteger() }.addAndGet(n) }
    fun rej(k: String, n: Int = 1) { photoRejects.getOrPut(k) { java.util.concurrent.atomic.AtomicInteger() }.addAndGet(n) }
    /** Página nova: contagens recomeçam (antes somavam todas as páginas e confundiam). */
    fun resetPage() { photoRejects.clear(); counters.clear(); lastImgs = -1; lastRaw = 0; lastCands = 0; lastKept = 0; photoCalls = 0; photoReplies = 0; photoErrs = 0; lastPhotoErr = ""; br.geracaoai.baixartg.core.BrowserProbeStats.fails.clear() }
    fun timed(ms: Long) { collects++; collectMs = ms; if (ms > collectMaxMs) collectMaxMs = ms }
    fun report(): String = buildString {
        appendLine("— navegador (página atual) — app v${br.geracaoai.baixartg.BuildConfig.VERSION_NAME}")
        appendLine("modo: ${listOf("só vídeos", "só fotos", "vídeos e fotos").getOrElse(BrowserDetect.mode.intValue) { "?" }}  nome: ${if (BrowserDetect.term.value.isBlank()) "-" else "sim"}")
        appendLine("lista: ${BrowserDetect.list.size} vídeos${if (BrowserDetect.mode.intValue == 1) " (escondidos: Só fotos)" else ""}, ${BrowserDetect.photos.size} fotos, carregando=${BrowserDetect.pending.intValue}, já na fila/enviados nesta página=${BrowserDetect.alreadyHere.intValue}")
        appendLine("páginas ocultas vivas: ${br.geracaoai.baixartg.core.JsRender.alive.get()} (fila: ${br.geracaoai.baixartg.core.JsRender.aliveQueue.get()})")
        appendLine("coleta: ${collects}× última=${collectMs}ms pior=${collectMaxMs}ms  <img> na página=$lastImgs")
        appendLine("fotos (coletor ${PhotoGrab.VERSION}): imagens brutas=$lastRaw candidatas=$lastCands na lista=$lastKept")
        appendLine("script de fotos: ${if (BrowserDetect.photosOn.value) "ligado" else "desligado"}  rodou=$photoCalls respondeu=$photoReplies erros=$photoErrs" + (if (lastPhotoErr.isNotBlank()) "  último erro: $lastPhotoErr" else ""))
        if (counters.isNotEmpty()) appendLine("contadores: " + counters.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}×${it.value.get()}" })
        if (photoRejects.isNotEmpty()) appendLine("fotos descartadas: " + photoRejects.entries.sortedByDescending { it.value.get() }.joinToString("; ") { "${it.key}×${it.value.get()}" })
        if (br.geracaoai.baixartg.core.BrowserProbeStats.fails.isNotEmpty()) appendLine("falhas ao medir foto: " + br.geracaoai.baixartg.core.BrowserProbeStats.fails.entries.sortedByDescending { it.value.get() }.joinToString("; ") { "${it.key}×${it.value.get()}" })
    }
}

object BrowserDetect {
    /** state: 0 = vídeo resolvido; 1 = carregando (página do card ainda sendo aberta); 2 = não carregou. */
    data class Det(val key: String, val f: Found, val sent: Boolean = false, val sel: Boolean = false, val keys: List<String> = emptyList(), val state: Int = 0)
    val list = mutableStateListOf<Det>()
    /** Termo da busca do painel: marca só os vídeos cujo título/texto do card/nome do arquivo têm todas as palavras. */
    val term = mutableStateOf("")

    fun matches(f: Found, t: String = term.value): Boolean {
        val w = NameFilter.words(t)
        if (w.isEmpty()) return true
        return NameFilter.matchesText(listOf(f.title, f.extra, Extract.fileName(f.url), Extract.fileName(f.page)).joinToString(" "), w)
    }

    /** Novo termo: marca só os que batem e desmarca todos os outros; já enviados nunca ficam marcados. */
    fun applyTerm(t: String) {
        term.value = t
        for (i in list.indices) { val d = list[i]; val v = !d.sent && matches(d.f, t); if (d.sel != v) list[i] = d.copy(sel = v) }
        // fotos: com nome, só ficam as que batem (as outras saem e podem voltar se o nome mudar)
        probing.clear(); photoEpoch++
        for (i in photos.indices) { val d = photos[i]; val v = matches(d.f, t); if (d.sel != v) photos[i] = d.copy(sel = v) }
    }

    // ---------------- Fotos (opcional, separado dos vídeos) ----------------
    val photos = mutableStateListOf<Det>()
    val photosOn = mutableStateOf(Prefs.mediaMode != 0)   // "Só fotos" e "Vídeos e fotos" detectam fotos (inclusive ao reabrir o app)
    /** 0 Só vídeos, 1 Só fotos, 2 Vídeos e fotos. "Só fotos" só esconde os vídeos (continuam detectados/salvos). */
    val mode = mutableIntStateOf(Prefs.mediaMode)
    fun setMode(m: Int) { val was = mode.intValue; Prefs.mediaMode = m; mode.intValue = m; setPhotosOn(m != 0); if (was == 1 && m != 1) fullCollect = true }
    /** Saiu de "Só fotos": a próxima coleta relê a página inteira (os vídeos não eram procurados nesse modo). */
    @Volatile var fullCollect = false
    private val photoKeys = HashSet<String>()
    private val thumbKeys = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Muda quando as fotos precisam ser relidas da página (nome trocado / fotos religadas). */
    @Volatile var photoEpoch = 0
    fun setPhotosOn(v: Boolean) { photosOn.value = v; photoEpoch++; if (!v) { photos.clear(); photoKeys.clear(); photoPosts.clear(); probing.clear() } }
    fun togglePhoto(key: String, v: Boolean) { val i = photos.indexOfFirst { it.key == key }; if (i >= 0) photos[i] = photos[i].copy(sel = v) }
    fun setAllPhotos(v: Boolean) { for (i in photos.indices) if (photos[i].sel != v) photos[i] = photos[i].copy(sel = v) }

    private var vtCache: HashSet<String>? = null; private var vtAt = 0L
    private fun videoTokens(): HashSet<String> {
        val now = System.currentTimeMillis()
        vtCache?.let { if (now - vtAt < 5_000) return it }
        return videoTokens0().also { vtCache = it; vtAt = now }
    }
    private fun videoTokens0(): HashSet<String> {
        val vt = HashSet<String>()
        // só vídeos de verdade (antes: toda página de card aberta, inclusive resultados de busca de imagens)
        list.forEach { if (it.state == 0) { vt += Photos.idTokens(it.f.url); vt += Photos.idTokens(it.f.page) } }
        return vt
    }
    /** Foto passa no nome (mesma regra estrita dos vídeos: legenda/título/alt/título do post). Sem nome: passa. */
    private fun photoMatches(f: Found) = term.value.isBlank() || matches(f)

    /** Adiciona fotos já conferidas (tamanho real ≥ 500 px). Só thread principal. */
    private fun addPhotos(page: String, ps: List<Photos.Photo>, checkThumbs: Boolean, ownPages: List<String> = emptyList()) {
        // a página do próprio post (e a aberta agora) não conta como "vídeo": antes o número do post no nome da foto
        // (album-555777-01.jpg) batia com a própria página e a foto era descartada como miniatura
        val own = HashSet<String>().apply { (ownPages + page + browsing).forEach { addAll(Photos.idTokens(it)) } }
        val vt = videoTokens().let { v -> if (own.isEmpty()) v else v.filterTo(HashSet()) { it !in own } }
        for (p in ps) {
            if (br.geracaoai.baixartg.web.AdBlock.isAdHost(Extract.host(p.url))) { BrowserDiag.rej("anúncio"); continue }
            val k = Photos.key(p.url)
            if (k in photoKeys) continue
            if ((checkThumbs && k in thumbKeys) || Photos.sharesId(p.url, vt)) { BrowserDiag.rej("miniatura de vídeo"); continue }
            val f = Found(p.url, page, Photos.title(p), Kind.PHOTO, ref = page, extra = p.text)
            // nome: igual aos vídeos — a foto aparece sempre; com nome, só as que batem já vêm marcadas
            // (antes as que não batiam sumiam: com o nome do professor digitado, quase nenhuma foto aparecia)
            val hit = photoMatches(f)
            BrowserDiag.inc(if (hit) "fotos listadas" else "fotos listadas sem marcar (nome não bate)")
            photoKeys += k
            if (Sent.isQueued(listOf(k)) || Sent.anySent(listOf(k)) || Sent.isSent(f)) { BrowserDiag.rej("já enviada/na fila"); continue }
            photos += Det("ph:$k", f, sel = term.value.isNotBlank() && hit, keys = listOf(k))
        }
    }
    private val photoPosts = java.util.Collections.synchronizedSet(HashSet<String>())
    private val probing = java.util.Collections.synchronizedSet(HashSet<String>())
    private val photoDeferred = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Photos.Card>>()
    private val photoGate = Semaphore(3)
    /** Cards de post de foto: abre a página do post (como o resolvedor de vídeo) e pega as fotos grandes de lá. */
    fun onPhotoCards(page: String, cards: List<Photos.Card>) {
        if (!photosOn.value || cards.isEmpty()) return
        val words = NameFilter.words(term.value)
        for (c in cards) {
            val np = normPage(c.href)
            // Antes: todo link com imagem também vira "card de vídeo" no detector de vídeos, e aqui era pulado por isso
            // → nenhum post de foto era aberto (fotos = 0). Agora só pula se já virou um vídeo de verdade.
            // abre o post na hora (antes esperava o detector de vídeo desistir dele: até 75 s por card, 3 por vez)
            if (!photoPosts.add(np)) continue
            BrowserDiag.inc("posts de foto achados")
            busy(1)
            scope.launch {
                try { photoGate.withPermit {
                    // só HTML (sem página oculta: elas dividem o motor de páginas com o navegador visível e travavam a rolagem)
                    val ps = try { Photos.fromPost(c.href, c.text, null, deadlineMs = 30_000) } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() }
                    val ok = ps   // nome só marca (addPhotos); nada some por causa do nome
                    BrowserDiag.inc("posts abertos"); if (ps.isEmpty()) BrowserDiag.rej("post: sem foto grande")
                    if (ok.isNotEmpty()) withContext(Dispatchers.Main) { addPhotos(c.href, ok, checkThumbs = false, ownPages = listOf(c.href, page)) }
                } } finally { busy(-1) }
            }
        }
    }

    private val staticDone = java.util.Collections.synchronizedSet(HashSet<String>())
    /**
     * Reforço sem depender do script na página: lê o HTML da página pela rede (com os cookies do navegador) e pega as fotos
     * dele; se a página aberta é a própria imagem, ela entra direto. Uma vez por página. Só thread principal.
     */
    fun staticPhotos(page: String) {
        if (!photosOn.value || !page.startsWith("http") || !staticDone.add(normPage(page) + "|" + photoEpoch)) return
        if (Extract.isPhotoUrl(page) && !Extract.isVideoUrl(page)) { onPhotos(page, listOf(Photos.Photo(page, "", 0, 0, orig = true))); return }
        busy(1)
        scope.launch {
            try {
                val h = kotlinx.coroutines.withTimeoutOrNull(20_000) { PageResolver.fetch(page, null) }
                val ps = h?.let { Photos.fromHtml(page, it) }.orEmpty()
                BrowserDiag.inc("fotos no HTML da página", ps.size)
                if (ps.isNotEmpty()) withContext(Dispatchers.Main) { onPhotos(page, ps) }
            } catch (e: Exception) { if (e is CancellationException) throw e } finally { busy(-1) }
        }
    }

    /** Miniatura (endereço mostrado na página) e tamanho de cada foto da lista. */
    val photoThumb = HashMap<String, String>(); val photoDim = HashMap<String, String>()
    /** v1.9.5: fotos do coletor simples. Só thread principal. Some só o que já está na fila/enviado (mesma foto exata). */
    fun onGrab(page: String, cands: List<PhotoGrab.Cand>) {
        if (!photosOn.value) return
        for (c in PhotoGrab.select(cands) { BrowserDiag.rej(it) }) {
            val k = PhotoGrab.key(c.url)
            if (k in photoKeys) continue
            photoKeys += k
            if (Sent.isQueued(listOf(k)) || Sent.anySent(listOf(k))) { BrowserDiag.rej("já enviada/na fila"); continue }
            val text = c.text.ifBlank { lastRefText }
            val title = TitleFix.clean(text.take(120)).ifBlank { Extract.fileName(c.url) }
            val f = Found(c.url, page, title, Kind.PHOTO, ref = page, extra = text)
            photoThumb["ph:$k"] = c.thumb; if (c.w > 0 && c.h > 0) photoDim["ph:$k"] = "${c.w}×${c.h}"
            photos += Det("ph:$k", f, sel = term.value.isNotBlank() && photoMatches(f), keys = listOf(k))
            BrowserDiag.lastKept++
        }
    }

    /** Fotos coletadas do DOM (Photos.JS). Nunca mexe na lista de vídeos. Só thread principal. */
    fun onPhotos(page: String, ps00: List<Photos.Photo>) {
        if (!photosOn.value || ps00.isEmpty()) return
        // foto aberta sozinha (endereço da própria imagem): herda o título/texto da página de onde você veio
        val ps0 = if (Extract.isPhotoUrl(page) && !Extract.isVideoUrl(page) && lastRefText.isNotBlank())
            ps00.map { it.copy(text = listOf(it.text, lastRefText).filter { t -> t.isNotBlank() }.joinToString(" ").take(500)) } else ps00
        BrowserDiag.inc("fotos candidatas", ps0.size)
        // sem tamanho certo (lazy, link para a original, versão reduzida conhecida): mede o arquivo de verdade.
        // Se não der pra medir, usa o tamanho da página; link para a original passa. Nada some sem motivo no diagnóstico.
        // (antes: no máximo 24 por vez e o resto sumia sem motivo; falha ao medir = descartada)
        val toMeasure = ps0.filter { (it.w <= 0 || it.h <= 0 || Photos.original(it.url) != null) && Photos.key(it.url) !in photoKeys && probing.add(it.url) }
        if (toMeasure.isNotEmpty()) { busy(1); scope.launch { try {
            val ok = java.util.Collections.synchronizedList(ArrayList<Photos.Photo>())
            val g = Semaphore(6)
            toMeasure.map { p -> async { g.withPermit {
                val (m, why) = Photos.measure(p, page)
                if (m == null) BrowserDiag.rej(why) else { ok += m; BrowserDiag.inc("aceitas: $why") }
            } } }.awaitAll()
            if (ok.isNotEmpty()) withContext(Dispatchers.Main) { addPhotos(page, ok.toList(), checkThumbs = true, ownPages = listOf(lastRefPage)) }
        } finally { busy(-1) } } }
        val ps = ps0.filter { it.w > 0 && it.h > 0 && Photos.original(it.url) == null }
        if (ps.isEmpty()) return
        addPhotos(page, ps, checkThumbs = true, ownPages = listOf(lastRefPage))
    }
    @Suppress("unused") private fun onPhotosOld(page: String, ps: List<Photos.Photo>) {
        if (!photosOn.value || ps.isEmpty()) return
        val vt = HashSet<String>()
        list.forEach { vt += Photos.idTokens(it.f.url); vt += Photos.idTokens(it.f.page) }
        hidden.values.forEach { vt += Photos.idTokens(it.f.page) }
        synchronized(cardPages) { cardPages.forEach { vt += Photos.idTokens(it) } }
        for (p in ps) {
            if (br.geracaoai.baixartg.web.AdBlock.isAdHost(Extract.host(p.url))) continue
            val k = Photos.key(p.url)
            if (k in photoKeys || k in thumbKeys || Photos.sharesId(p.url, vt)) continue
            photoKeys += k
            val f = Found(p.url, page, Photos.title(p), Kind.PHOTO, ref = page, extra = p.text)
            if (Sent.isQueued(listOf(k)) || Sent.anySent(listOf(k)) || Sent.isSent(f)) continue
            photos += Det("ph:$k", f, sel = term.value.isNotBlank() && matches(f), keys = listOf(k))
        }
    }

    /** Baixar fotos marcadas: saem da lista na hora e nunca voltam (mesmo histórico de enviados). */
    fun sendPhotos(ds: List<Det>, onMsg: (String) -> Unit) {
        val ks = ds.map { it.key }.toHashSet()
        val todo = photos.filter { it.key in ks }
        if (todo.isEmpty()) { onMsg("Nada novo para enviar."); return }
        photos.removeAll { it.key in ks }
        todo.forEach { d -> Sent.markQueued(d.keys + ("pg:" + pgNorm(d.f.url))) }
        Pipeline.add(todo.map { d -> Pipeline.In(d.f.page, d.f.title, d.f.extra, d.keys, d.f, photo = true) })
        onMsg("Enviando ${todo.size} foto(s). Acompanhe na Fila.")
    }
    fun toggle(key: String, v: Boolean) { val i = list.indexOfFirst { it.key == key }; if (i >= 0 && !list[i].sent) list[i] = list[i].copy(sel = v) }
    val pending = mutableIntStateOf(0)
    /** Cards desta página que já estão na fila ou já foram enviados (não são abertos de novo). */
    val alreadyHere = mutableIntStateOf(0)
    /** Fotos sendo conferidas agora (posts abertos / tamanhos medidos). */
    val photoBusy = mutableIntStateOf(0)
    private fun busy(d: Int) { scope.launch(Dispatchers.Main) { photoBusy.intValue = (photoBusy.intValue + d).coerceAtLeast(0) } }
    /** Quando a página atual começou a abrir (para o "Procurando…" ter fim). */
    val pageAt = androidx.compose.runtime.mutableLongStateOf(0L)
    fun newPage() { alreadyHere.intValue = 0; pageAt.longValue = System.currentTimeMillis(); BrowserDiag.resetPage() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(3)
    /** Posts de foto que precisam de WebView: no máximo 1 por vez, para não tirar vagas dos vídeos. */
    private val photoRender = Semaphore(1)
    private val seenCards = HashSet<String>()
    private val processed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val keys = HashMap<String, String>()

    fun reset() {
        loadedHosts.clear(); relaunched = 0
        synchronized(seenCards) {
            seenCards.clear(); keys.clear(); processed.clear()
            hidden.values.forEach { d -> d.keys.forEach { keys[it] = d.key }; keys[d.key] = d.key }
        }
        list.clear(); followed.clear()
    }
    /** Páginas de card (página de UM vídeo) — só elas viram chave permanente de "já pedido". */
    private val cardPages = java.util.Collections.synchronizedSet(HashSet<String>())
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val followed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val DURATION = Regex("""(?:^|\s)(?:\d{1,2}:)?\d{1,2}:\d{2}(?:\s|$)""")

    /** Card ainda sem vídeo: aparece já na lista como "carregando…" (dá pra marcar antes de resolver). */
    private fun placeholder(card: String, title: String, ctx: String) {
        val pk = listOf("ip:" + normPage(card), "p:" + normPage(card))
        val f = Found(card, card, title.ifBlank { "vídeo" }, Kind.SERVER, extra = listOf(title, ctx).joinToString(" ").trim())
        scope.launch {
            // conferência completa (fila/enviados/lista da busca) fora da thread da tela
            val isBlocked = try { blocked(f, pk) } catch (_: Exception) { false }
            withContext(Dispatchers.Main) { placeholderMain(card, f, pk, isBlocked) }
        }
    }
    private fun placeholderMain(card: String, f: Found, pk: List<String>, isBlocked: Boolean) {
        run {
            if (synchronized(seenCards) { pk.any { keys.containsKey(it) } }) return
            val k = "card:" + normPage(card)
            if (isBlocked) { synchronized(seenCards) { pk.forEach { keys[it] = "x:$k" } }; return }
            synchronized(seenCards) { pk.forEach { keys[it] = k }; keys[k] = k }
            list += Det(k, f, sel = matches(f), keys = pk, state = 1)
        }
    }
    private fun markFailed(card: String) = scope.launch(Dispatchers.Main) {
        // vídeo não carregou: se o card também parecia post de foto, agora procura as fotos dele
        val hk = "card:" + normPage(card)
        hidden[hk]?.let { if (it.state == 1) hidden[hk] = it.copy(state = 2) }
        val i = list.indexOfFirst { it.key == "card:" + normPage(card) && it.state == 1 }
        if (i >= 0) list[i] = list[i].copy(state = 2)
    }

    private fun isRealVideo(f: Found): Boolean =
        f.kind != Kind.PHOTO && !(Extract.isPhotoUrl(f.url) && !Extract.isVideoUrl(f.url)) &&
            // "capa/thumb/poster" no caminho só derruba quando NÃO é arquivo de vídeo (antes derrubava até .mp4 de verdade)
            !(Extract.isVideoThumb(f.url) && !Extract.isVideoUrl(f.url) && !Extract.isHls(f.url)) &&
            (f.kind == Kind.SERVER || f.kind == Kind.HLS || Extract.isVideoUrl(f.url) || f.kind == Kind.DIRECT)

    /**
     * Adiciona (ou troca por qualidade maior) — dedupe por identidade do vídeo.
     * As conferências de "já enviado/na fila" (histórico inteiro + fila) rodam FORA da tela; só o resultado vai
     * para a thread principal (antes travava a tela a cada vídeo detectado). Mesmas regras de antes.
     */
    private fun publish(f0: Found, extraKeys: List<String> = emptyList()) {
        val f = MediaIdentity.withQuality(f0)
        if (!isRealVideo(f) || TitleFix.isSubtitleUrl(f.url)) return
        val ks = (extraKeys + dedupeKeys(f)).distinct()
        val ksPage = ks.filter { it.startsWith("ip:") || it.startsWith("p:") }
        scope.launch {
            var preKey: String? = null; var preSent = false; var preBlocked = false
            try {
                preKey = synchronized(seenCards) { ks.firstNotNullOfOrNull { keys[it] } }
                val cur = preKey?.let { k -> list.toList().firstOrNull { it.key == k } }
                if (cur != null) {
                    val better = cur.state != 0 || MediaIdentity.isBetter(f, cur.f)
                    val nf = if (better) f else cur.f
                    val pk = (cur.keys + ksPage).distinct()
                    preSent = cur.sent || Sent.isSent(f) || Sent.hasCanonical(f) || Jobs.isQueuedOrSent(nf, pk)
                } else if (preKey == null) preBlocked = blocked(f, ks)
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) { publishMain(f, ks, ksPage, preKey, preSent, preBlocked) }
        }
    }
    private fun publishMain(f: Found, ks: List<String>, ksPage: List<String>, preKey: String?, preSent: Boolean, preBlocked: Boolean) {
        val hitKey = synchronized(seenCards) { ks.firstNotNullOfOrNull { keys[it] } }
        if (hitKey != null) {
            // item que o usuário já mandou baixar enquanto ainda carregava: guarda o vídeo resolvido para enviar
            hidden[hitKey]?.let { h ->
                if (h.state != 0 || MediaIdentity.isBetter(f, h.f)) {
                    val nf = if (h.state != 0 && !TitleFix.isJunk(h.f.title) && TitleFix.isJunk(f.title)) f.copy(title = h.f.title) else f
                    hidden[hitKey] = h.copy(f = nf, state = 0, keys = (h.keys + ksPage).distinct())
                }
                synchronized(seenCards) { ks.forEach { keys[it] = hitKey } }
                return
            }
            val i = list.indexOfFirst { it.key == hitKey }
            if (i >= 0) {
                val better = list[i].state != 0 || MediaIdentity.isBetter(f, list[i].f)
                val nf = if (better) f else list[i].f
                val pk = (list[i].keys + ksPage).distinct()
                // conferido fora da tela; se o item mudou nesse meio tempo, confere de novo (raro)
                val sent2 = if (preKey == hitKey) preSent || list[i].sent
                    else list[i].sent || Sent.isSent(f) || Sent.hasCanonical(f) || Jobs.isQueuedOrSent(nf, pk)
                val keepTitle = if (list[i].state != 0 && !TitleFix.isJunk(list[i].f.title) && TitleFix.isJunk(nf.title)) nf.copy(title = list[i].f.title) else nf
                if (sent2) list.removeAt(i)
                else list[i] = list[i].copy(f = keepTitle, sel = list[i].sel, keys = pk, state = 0)
            }
            synchronized(seenCards) { ks.forEach { keys[it] = hitKey } }
            return
        }
        val k = ks.first()
        // já mandado baixar/enviado (nesta ou em outra sessão, mesmo com outra URL): nunca reaparece
        val isBlocked = if (preKey == null) preBlocked else blocked(f, ks)
        if (isBlocked) { synchronized(seenCards) { ks.forEach { keys[it] = "x:$k" } }; return }
        synchronized(seenCards) { ks.forEach { keys[it] = k } }
        list += Det(k, f, sel = matches(f), keys = ksPage)
    }

    private val QUALITY_TOK = Regex("""(?i)(?:[_.\-/]|^)(?:\d{3,4}p\d{0,2}|[1-8]k|uhd|fhd|hd|sd|ld|hq|lq|low|high|medium|mobile|source|original|\d{3,4}x\d{3,4}|\d{3,5}k)(?=$|[_.\-/])""")
    private val GENERIC_STEM = setOf("index", "master", "playlist", "video", "media", "manifest", "stream", "chunklist", "file", "download", "embed", "play", "watch", "")

    private fun normPage(u: String) = PageKey.norm(u)
    /** Chave "pg:" da página: igual à de antes, mas com o parâmetro que identifica a página quando o caminho é genérico. */
    private fun pgNorm(u: String) = Sent.norm(u) + PageKey.idQuery(u)
    private fun normMedia(u: String): String {
        val noQ = u.substringBefore('#').substringBefore('?').lowercase()
        val path = noQ.substringAfter("://").substringAfter('/')
        return QUALITY_TOK.replace(path, "").replace(Regex("/+"), "/")
    }
    private fun stem(u: String): String {
        val n = Extract.fileName(u).lowercase().substringBeforeLast('.')
        val s = QUALITY_TOK.replace(n, "").trim('_', '-', '.', ' ')
        return if (s.length < 6 || s in GENERIC_STEM) "" else s
    }

    /** Chaves de identidade: página canônica do card, URL de mídia normalizada, nome do arquivo e título normalizado. */
    private fun dedupeKeys(f: Found): List<String> {
        val out = LinkedHashSet<String>()
        out += MediaIdentity.keys(f)
        out += "u:" + f.url.substringBefore('#')
        if (f.kind == Kind.SERVER) out += "p:" + normPage(f.url)
        if (f.page.isNotBlank() && f.page != browsing) out += "p:" + normPage(f.page)
        if (f.kind != Kind.SERVER) out += "m:" + normMedia(f.url)
        stem(f.url).takeIf { it.isNotBlank() }?.let { out += "s:$it" }
        val t = NameFilter.norm(f.title).trim()
        if (t.length >= 8 && t != NameFilter.norm(Extract.fileName(f.url))) out += "t:$t"
        return out.toList()
    }

    /** Página aberta no navegador agora. */
    @Volatile var browsing: String = ""
    /** Título e texto (descrição/canal/autor) de cada página vista, para nomear e filtrar as mídias interceptadas. */
    private val pageInfo = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    // ---------------- lista salva no disco (sobrevive a fechar/atualizar o app) ----------------
    private val loadedHosts = HashSet<String>()
    /** Repõe a lista salva deste site (aditivo). Itens já enviados/na fila não voltam. */
    /**
     * Lista salva do site volta como estava. Lê e filtra o arquivo fora da thread principal e aplica na tela
     * aos poucos (o navegador abre na hora, mesmo com milhares de itens salvos e a fila rodando).
     */
    fun restore(host: String) {
        if (host.isBlank() || !loadedHosts.add(host)) return
        scope.launch {
            val (vs, ps) = BrowserStore.loadPanel(host)
            val okV = vs.filter { d -> !blocked(d.f, d.keys + dedupeKeys(d.f)) }
            val okP = if (photosOn.value) ps.filter { d -> val k = d.keys.firstOrNull(); k != null && !Sent.isQueued(listOf(k)) && !Sent.anySent(listOf(k)) } else emptyList()
            for (chunk in okV.chunked(200)) withContext(Dispatchers.Main) {
                if (host !in loadedHosts) return@withContext
                val have = list.mapTo(HashSet()) { it.key }
                val add = ArrayList<Det>(chunk.size)
                for (d in chunk) {
                    if (!have.add(d.key)) continue
                    val pk = d.keys
                    synchronized(seenCards) {
                        (pk + dedupeKeys(d.f)).forEach { if (!keys.containsKey(it)) keys[it] = d.key }; keys[d.key] = d.key
                        if (d.key.startsWith("card:") || d.state != 0) { seenCards += d.f.page; cardPages += normPage(d.f.page) }
                    }
                    add += d.copy(sent = false)
                    if (d.state == 1 && relaunched < 60 && mode.intValue != 1) { relaunched++; resolve(d.f.page, d.f.title, d.f.page, d.f.extra) }
                }
                list.addAll(add)
            }
            if (okP.isNotEmpty()) withContext(Dispatchers.Main) {
                for (d in okP) { val k = d.keys.first(); if (k in photoKeys) continue; photoKeys += k; photos += d }
            }
        }
    }
    private var relaunched = 0
    fun loadedHostSet(): Set<String> = loadedHosts.toSet()

    /** Itens mandados baixar que ainda estavam carregando (já saíram da lista). Só na thread principal. */
    private val hidden = HashMap<String, Det>()
    /** Itens esperando a tarefa atual terminar para entrar na fila de envio. */
    private val outbox = ArrayList<Det>()
    private var outboxJob: Job? = null

    /** Todas as chaves de um item (página do card, URL do vídeo e variantes) para nunca reaparecer. */
    private fun allKeys(d: Det): List<String> =
        ((d.keys + dedupeKeys(d.f)).filter { k -> !k.startsWith("t:") &&
            (!(k.startsWith("ip:") || k.startsWith("p:")) || k.substringAfter(':') in cardPages || d.f.kind == Kind.SERVER && k.substringAfter(':') == normPage(d.f.url)) } + Sent.keysOf(d.f) + ("pg:" + pgNorm(d.f.url)) +
            (if (d.f.page.isNotBlank() && d.key.startsWith("card:")) listOf("pg:" + pgNorm(d.f.page)) else emptyList())).distinct()

    private fun blocked(f: Found, ks: List<String>): Boolean {
        val all = ks + Sent.keysOf(f) + ("pg:" + pgNorm(f.url)) + (if (f.page != browsing) listOf("pg:" + pgNorm(f.page)) else emptyList())
        return Sent.isQueued(all) || Sent.anySent(all) || Jobs.isQueuedOrSent(f, ks.filter { it.startsWith("ip:") || it.startsWith("p:") })
    }

    /** Some da lista na hora, grava como "já pedido" e entra na fila (se houver envio rodando, entra assim que ele terminar). */
    private fun dispatch(ds: List<Det>) {
        if (ds.isEmpty()) return
        ds.forEach { d -> val ak = allKeys(d); Sent.markQueued(ak); synchronized(seenCards) { ak.forEach { k -> if (!keys.containsKey(k)) keys[k] = "x:" + d.key } } }
        // fila persistente: resolvidos vão direto para baixar/enviar; os "carregando…" a fila abre a página sozinha
        Pipeline.add(ds.map { d ->
            val page = d.f.page.ifBlank { d.f.url }
            if (d.state == 0) Pipeline.In(page, d.f.title, d.f.extra, d.keys, d.f, uniq = d.key)
            else Pipeline.In(page, d.f.title, d.f.extra, d.keys, null, uniq = d.key)
        })
    }

    private fun runOutbox() {
        if (outboxJob?.isActive == true) return
        outboxJob = scope.launch(Dispatchers.Main) {
            while (outbox.isNotEmpty()) {
                val photo = outbox.first().f.kind == Kind.PHOTO
                val batch = outbox.filter { (it.f.kind == Kind.PHOTO) == photo }
                val n = Jobs.enqueue(batch.map { Triple(it.f, -1L, it.keys) }, start = true)
                if (n >= 0) outbox.removeAll(batch.toSet()) else delay(2000)
            }
        }
    }

    /** Envia os escolhidos uma vez só: saem da lista imediatamente e nunca mais aparecem. */
    fun send(ds: List<Det>): String {
        val todo = ds.filter { !it.sent }
        if (todo.isEmpty()) return "Nada novo para enviar."
        val ks = todo.map { it.key }.toHashSet()
        list.removeAll { it.key in ks }
        todo.filter { it.state != 0 }.forEach { d -> jobs.remove(normPage(d.f.page))?.cancel() }
        dispatch(todo)
        return "${todo.size} vídeo(s) na fila de envio (veja na Fila)."
    }

    fun setAll(v: Boolean) { for (i in list.indices) { val d = list[i]; val nv = v && !d.sent; if (d.sel != nv) list[i] = d.copy(sel = nv) } }

    /** Mídia interceptada pelo navegador (pedido de rede de vídeo). */
    fun intercepted(url: String, page: String, title: String) {
        if (page.isNotBlank()) browsing = page
        if (mode.intValue == 1) return   // "Só fotos": não procura vídeo nenhum
        val kind = Extract.mediaKindOf(url) ?: return
        if (Extract.isVideoThumb(url) || url.contains("preview", true) || TitleFix.isSubtitleUrl(url)) return
        val info = pageInfo[normPage(page)]
        val t = TitleFix.pick(info?.first, title).ifBlank { Extract.mediaTitle(url, "") }
        val extra = listOfNotNull(info?.first, info?.second, TitleFix.clean(title)).filter { it.isNotBlank() }.distinct().joinToString(" ")
        // tudo que o player da página pede (variantes de qualidade, faixas de idioma) = um vídeo só, o de maior qualidade
        publish(Found(url, page, t, kind, ref = page, extra = extra), listOf("ip:" + normPage(page)))
    }

    /** Cards coletados do DOM (JsScripts.COLLECT). */
    fun onCollected(page: String, c: JsScripts.Collected, background: Boolean = false) {
        if (!background) browsing = page
        val prevInfo = pageInfo.put(normPage(page), c.bestPageTitle to c.pageText)
        if (!background && !(Extract.isPhotoUrl(page) && !Extract.isVideoUrl(page))) {
            lastRefPage = page
            lastRefText = listOf(c.bestPageTitle, prevInfo?.first.orEmpty().takeIf { c.bestPageTitle.isBlank() }.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
        }
        // mídias já listadas desta página ganham o título real (antes podiam ter vindo como "arquivo"/idioma)
        val pt = c.bestPageTitle
        // só quando o título da página muda (antes percorria a lista inteira a cada coleta, na thread da tela)
        if (pt.isNotBlank() && prevInfo?.first != pt) scope.launch(Dispatchers.Main) {
            for (i in list.indices) { val d = list[i]
                if (normPage(d.f.page) == normPage(page) && (TitleFix.isJunk(d.f.title) || d.f.title == Extract.fileName(d.f.url))) {
                    val nf = d.f.copy(title = pt, extra = listOf(pt, c.pageText, d.f.extra).joinToString(" ").trim())
                    list[i] = d.copy(f = nf, sel = !d.sent && matches(nf))
                }
            }
        }
        val root = Extract.host(page)
        if (mode.intValue != 1) for (v in c.videos) if (!v.startsWith("blob:")) intercepted(v, page, "")
        // cada link é examinado uma vez só (antes: todos os links da página, a cada coleta, na thread da tela)
        for (a in c.anchors) {
            val href = a.href.substringBefore('#')
            // (volta a examinar se o link ganhar miniatura/texto depois — lazy-load)
            // miniatura de card de VÍDEO (link de página de vídeo ou com duração): só a imagem do próprio link (ou do pai).
            // Antes todo link com imagem contava (resultados de busca de imagens viravam "miniatura de vídeo").
            if (a.thumb.startsWith("http") && a.thumbLevel <= 1 && !(Extract.isPhotoUrl(href) && !Extract.isVideoUrl(href)) &&
                (Extract.looksLikeVideoPage(a.href) || DURATION.containsMatchIn(a.ctx))) thumbKeys += Photos.key(a.thumb)
            if (mode.intValue == 1) continue   // "Só fotos": não abre card de vídeo nenhum (relido inteiro se o modo mudar)
            if (!processed.add(href + "|" + a.thumb.isNotBlank() + "|" + a.ctx.length + "|" + a.nav)) continue
            if (a.nav) continue
            if (a.thumb.isBlank() && !Extract.looksLikeVideoPage(a.href) && !DURATION.containsMatchIn(a.ctx)) continue
            if (Extract.isPhotoUrl(href) && !Extract.isVideoUrl(href)) continue
            if (Extract.host(href) != root && !Extract.YTDLP_HOSTS.containsMatchIn(href)) continue
            if (href.trimEnd('/') == page.substringBefore('#').trimEnd('/')) continue
            if (SiteSearch.isSearchUrl(href)) continue
            if (!synchronized(seenCards) { seenCards.add(href) }) continue
            resolve(href, a.bestTitle, page, a.ctx)
        }
        // paginação dos resultados ("próxima", rel=next): segue sozinho quando há busca/nome
        val nx = c.next.substringBefore('#')
        if (nx.startsWith("http") && Extract.host(nx) == root && nx != page && (term.value.isNotBlank() || SiteSearch.isSearchUrl(page) || background) &&
            followed.size < 30 && followed.add(normPage(nx) + "?" + nx.substringAfter('?', ""))) {
            scope.launch {
                // próxima página: só o HTML (sem página oculta: ela dividia o motor de páginas com o navegador visível)
                val h = PageResolver.fetch(nx, page)
                val col = h?.let { staticCollect(nx, it) }
                if (col != null && col.anchors.isNotEmpty()) withContext(Dispatchers.Main) { onCollected(nx, col, background = true) }
                // fotos também nas próximas páginas da busca ("Só fotos" / "Vídeos e fotos")
                if (photosOn.value && h != null) {
                    val ps = Photos.fromHtml(nx, h); val cs = Photos.cardsFromHtml(nx, h)
                    withContext(Dispatchers.Main) { onPhotos(nx, ps); onPhotoCards(nx, cs) }
                }
            }
        }
    }

    /** Página anterior (não-imagem) aberta no navegador: título/texto herdados por uma foto aberta sozinha. */
    @Volatile private var lastRefPage = ""
    @Volatile private var lastRefText = ""

    private val CARD_SEL = "article,li,figure,[class*=card],[class*=item],[class*=video],[class*=post],[class*=thumb]"
    private val NEXT_TXT = Regex("""(?i)^(pr[oó]xim[ao]|next|seguinte|mais|›|»|>|>>|next page|pr[oó]xima p[aá]gina)$""")
    /** Mesmo formato da coleta do navegador, a partir do HTML (próximas páginas da busca). */
    private fun staticCollect(url: String, html: String): JsScripts.Collected? = try {
        val doc = org.jsoup.Jsoup.parse(html, url)
        val seen = HashSet<String>(); val anchors = ArrayList<JsScripts.Anchor>()
        for (a in doc.select("a[href]")) {
            val h = a.absUrl("href"); if (!h.startsWith("http") || !seen.add(h)) continue
            val img = a.selectFirst("img[alt]")
            val hd = a.selectFirst("h1,h2,h3,h4,[class*=title]")
            val txt = (listOfNotNull(hd?.text()) + listOf(a.attr("title"), a.attr("aria-label"), a.text(), img?.attr("alt").orEmpty())).filter { it.trim().length > 1 }.joinToString("\n").take(400)
            var th = ""; var lvl = 9; var p: org.jsoup.nodes.Element? = a; var k = 0
            while (p != null && k < 4) { val i = p.selectFirst("img,picture,video,[style*=background-image]"); if (i != null) { th = i.absUrl("src").ifBlank { i.absUrl("data-src") }.ifBlank { "1" }; lvl = k; break }; p = p.parent(); k++ }
            val nav = a.parents().any { it.tagName() in setOf("nav", "header", "footer") }
            val ctx = a.closest(CARD_SEL)?.text()?.replace(Regex("\\s+"), " ")?.trim()?.take(300).orEmpty()
            anchors += JsScripts.Anchor(h, txt, th, nav, ctx, lvl)
            if (anchors.size >= 4000) break
        }
        val nx = doc.selectFirst("link[rel=next],a[rel~=next]")?.absUrl("href").orEmpty().ifBlank {
            doc.select("a[href]").firstOrNull { val t = it.text().trim().ifBlank { it.attr("aria-label").trim() }; t.length < 20 && NEXT_TXT.matches(t) }?.absUrl("href").orEmpty() }
        val vids = doc.select("video[src],video source[src],source[src]").map { it.absUrl("src") }.filter { it.startsWith("http") }.distinct()
        val pt = listOf(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty(), doc.selectFirst("meta[name=twitter:title]")?.attr("content").orEmpty(), doc.selectFirst("h1")?.text().orEmpty(), doc.title()).filter { it.length > 1 }
        val pd = listOf(doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty(), doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()).joinToString(" ").take(600)
        JsScripts.Collected(doc.title(), false, anchors, vids, url, pt, pd, nx)
    } catch (_: Exception) { null }

    /** Baixar / Baixar marcados: resolve antes os que ainda estão carregando, depois envia (uma vez só). */
    fun sendAsync(ds: List<Det>, onMsg: (String) -> Unit) {
        scope.launch(Dispatchers.Main) {
            val want = ds.map { it.key }.toHashSet()
            // pega a versão atual de cada item (pode ter acabado de carregar)
            // "não carregou" também vai: a fila abre a página de novo e tenta o servidor
            val cur = list.filter { it.key in want && !it.sent }
            if (cur.isEmpty()) { onMsg("Nada novo para enviar."); return@launch }
            val curKeys = cur.map { it.key }.toHashSet()
            list.removeAll { it.key in curKeys }   // somem da lista na hora
            // os que ainda carregavam param aqui: a fila abre cada página sozinha, sem esperar os outros
            cur.filter { it.state != 0 }.forEach { d -> jobs.remove(normPage(d.f.page))?.cancel() }
            dispatch(cur)
            onMsg("${cur.size} vídeo(s) na fila de envio. Podem ir até 10 ao mesmo tempo (veja na Fila).")
        }
    }

    private fun resolve(card: String, title: String, page: String, cardCtx: String = "") {
        if (mode.intValue == 1) return   // "Só fotos": nenhuma página de vídeo é aberta
        // Causa do "Procurando vídeos…" sem fim: cards que já estavam na fila/enviados eram abertos de novo
        // (página + WebView com play + servidor), ocupando as 3 vagas do navegador por muito tempo; os novos
        // ficavam esperando atrás deles e a lista não aparecia. Agora já na fila/enviado = pula na hora.
        if (!Extract.isVideoUrl(card)) {
            val pk = listOf("ip:" + normPage(card), "p:" + normPage(card))
            // só consultas rápidas aqui (thread da tela); a conferência completa roda fora dela, no placeholder
            val skip = try { val all = pk + ("pg:" + pgNorm(card)); Sent.isQueued(all) || Sent.anySent(all) } catch (_: Exception) { false }
            if (skip) {
                val k = "card:" + normPage(card)
                synchronized(seenCards) { pk.forEach { if (!keys.containsKey(it)) keys[it] = "x:$k" } }
                scope.launch(Dispatchers.Main) { alreadyHere.intValue++ }
                return
            }
        }
        scope.launch(Dispatchers.Main) { pending.intValue++ }
        val t0 = TitleFix.pick(title, cardCtx.take(150))
        cardPages += normPage(card)
        if (!Extract.isVideoUrl(card)) placeholder(card, t0, cardCtx)
        jobs[normPage(card)] = scope.launch {
            try {
                if (!Extract.isVideoUrl(card) && blocked(Found(card, card, "vídeo", Kind.SERVER), listOf("ip:" + normPage(card), "p:" + normPage(card)))) {
                    withContext(Dispatchers.Main) { alreadyHere.intValue++ }; return@launch
                }
                // cada card tem prazo: nada fica "carregando…" para sempre
                val ok = withTimeoutOrNull(75_000) { gate.withPermit {
                    var t = TitleFix.pick(title, cardCtx.take(150))
                    val tEarly = t.ifBlank { TitleFix.pick(pageInfo[normPage(page)]?.first, Extract.fileName(card)).ifBlank { "vídeo" } }
                    if (Extract.isVideoUrl(card)) { publish(Found(card, page, tEarly, Extract.mediaKindOf(card) ?: Kind.DIRECT, ref = page, extra = tEarly)); return@withPermit }
                    if (Extract.YTDLP_HOSTS.containsMatchIn(card)) { publish(Found(card, card, tEarly, Kind.SERVER, extra = tEarly)); return@withPermit }
                    val html = try {
                        Net.browser.newCall(Request.Builder().url(card).header("Referer", page).build()).await().use { r ->
                            val ct = r.header("Content-Type") ?: ""
                            if (r.isSuccessful && (ct.isEmpty() || ct.contains("html", true))) r.body?.string()?.take(1_500_000) else null
                        }
                    } catch (e: Exception) { if (e is CancellationException) throw e; null }
                    val pr = html?.let { h -> Extract.parse(card, h) }
                    if (t.isBlank()) t = TitleFix.pick(pr?.title, Extract.fileName(card).replace('-', ' ').replace('_', ' ')).ifBlank { "vídeo" }
                    val ctxAll = listOfNotNull(t, cardCtx, pr?.title, pr?.pageText).filter { it.isNotBlank() }.distinct().joinToString(" ").take(1200)
                    val vids = pr?.let { r -> r.videos.map { MediaIdentity.withQuality(it) }.filter { isRealVideo(it) && !it.url.contains("preview", true) } }.orEmpty()
                    val best = vids.filter { it.kind != Kind.SERVER || vids.none { o -> o.kind != Kind.SERVER } }
                        .reduceOrNull { a, b -> if (MediaIdentity.isBetter(b, a)) b else a }
                    if (best != null) { publish(best.copy(page = card, title = t, extra = listOf(ctxAll, best.extra).joinToString(" ").trim()), listOf("ip:" + normPage(card))); return@withPermit }
                    // "Só fotos": não abre página oculta nem servidor para card de vídeo (só pesaria; os vídeos ficam escondidos)
                    if (mode.intValue == 1) { markFailed(card); return@withPermit }
                    // vídeo só carrega depois de abrir a página e tocar em "assistir"/play: WebView oculto com play automático
                    val (realTitle, wv) = PageResolver.viaWebView(card) { u -> JsRender.render(u, 20_000, play = true) }
                    val tt = TitleFix.pick(realTitle, t).ifBlank { t }
                    if (wv != null) {
                        publish(wv.copy(page = card, ref = wv.ref ?: card, title = tt, extra = listOf(tt, ctxAll, wv.extra).joinToString(" ").trim()), listOf("ip:" + normPage(card)))
                        return@withPermit
                    }
                    val pt = withTimeoutOrNull(40_000) { Downloader.probe(Prefs.server, card, Net.browserSlow) }
                    if (pt != null) publish(Found(card, card, TitleFix.pick(tt, pt).ifBlank { pt }, Kind.SERVER, extra = listOf(ctxAll, pt).joinToString(" ")), listOf("ip:" + normPage(card)))
                    else markFailed(card)
                } }
                if (ok == null) markFailed(card)
            } catch (e: Exception) { if (e is CancellationException) throw e; markFailed(card) }
            finally { withContext(NonCancellable + Dispatchers.Main) { pending.intValue-- } }
        }
    }

    fun warm() { scope.launch { Downloader.warmUp(Prefs.server) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserVideosSheet(onDismiss: () -> Unit, onSent: (String) -> Unit, onSearchSite: (String) -> Unit) {
    var onlyChecked by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf(BrowserDetect.term.value) }
    val all = BrowserDetect.list
    val visible = all.filter { !it.sent && (!onlyChecked || it.sel) }
    val total = all.count { !it.sent }
    val checked = all.filter { it.sel && !it.sent }
    val pending by BrowserDetect.pending
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(q, { q = it; BrowserDetect.applyTerm(it) }, Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (q.isNotEmpty()) IconButton({ q = ""; BrowserDetect.applyTerm("") }) { Icon(Icons.Rounded.Close, "Limpar") } },
                label = { Text("Nome (marca só os com esse nome)") }, shape = MaterialTheme.shapes.medium)
            OutlinedButton({ onSearchSite(q) }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = q.isNotBlank()) {
                Icon(Icons.Rounded.TravelExplore, null); Spacer(Modifier.width(8.dp)); Text("Pesquisar no site") }
            val photosOn by BrowserDetect.photosOn
            val mm by BrowserDetect.mode
            MediaModeSelector(mm) { BrowserDetect.setMode(it); tab = if (it == 1) 1 else 0 }
            Text("$total vídeo${if (total == 1) "" else "s"}" + (if (mm != 0) " • ${BrowserDetect.photos.size} foto${if (BrowserDetect.photos.size == 1) "" else "s"}" else ""),
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mm == 2) TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Vídeos ($total)") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Fotos (${BrowserDetect.photos.size})") })
            }
            if (mm == 0 || (mm == 2 && tab == 0)) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${checked.size} de $total marcados", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (pending > 0) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)); Text("resolvendo $pending", style = MaterialTheme.typography.labelMedium) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ BrowserDetect.setAll(true) }, Modifier.weight(1f), enabled = checked.size < total) { Text("Marcar todos", maxLines = 1) }
                    OutlinedButton({ BrowserDetect.setAll(false) }, Modifier.weight(1f), enabled = checked.isNotEmpty()) { Text("Desmarcar todos", maxLines = 1) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mostrar só os marcados", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(onlyChecked, { onlyChecked = it })
                }
                Button({
                    BrowserDetect.sendAsync(checked, onSent)
                }, Modifier.fillMaxWidth().padding(top = 4.dp), enabled = checked.isNotEmpty()) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp)); Text("Baixar marcados (${checked.size})") }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(visible, key = { it.key }) { d ->
                        ListItem(
                            headlineContent = { Text(d.f.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(when (d.state) { 1 -> "carregando…"; 2 -> "não carregou aqui (a fila tenta de novo)"; else -> d.f.typeLabel + " • " + (if (d.f.kind == Kind.SERVER) "via servidor" else "pronto") } + if (d.sent) " • Já enviado" else "") },
                            leadingContent = { Checkbox(d.sel && !d.sent, { v -> BrowserDetect.toggle(d.key, v) }, enabled = !d.sent) },
                            trailingContent = {
                                TextButton({ BrowserDetect.sendAsync(listOf(d), onSent) }, enabled = !d.sent) { Text("Baixar") }
                            })
                    }
                }
            } else {
                val ph = BrowserDetect.photos
                val phChecked = ph.filter { it.sel }
                Text("${phChecked.size} de ${ph.size} fotos marcadas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                val busyPh by BrowserDetect.photoBusy
                if (ph.isEmpty()) Text(if (busyPh > 0) "Conferindo as fotos desta página…" else "Nenhuma foto nesta página ainda. Role a página para carregar as fotos.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ BrowserDetect.setAllPhotos(true) }, Modifier.weight(1f), enabled = phChecked.size < ph.size) { Text("Marcar todos", maxLines = 1) }
                    OutlinedButton({ BrowserDetect.setAllPhotos(false) }, Modifier.weight(1f), enabled = phChecked.isNotEmpty()) { Text("Desmarcar todos", maxLines = 1) }
                }
                Button({ BrowserDetect.sendPhotos(phChecked, onSent) }, Modifier.fillMaxWidth().padding(top = 4.dp), enabled = phChecked.isNotEmpty()) {
                    Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp)); Text("Baixar marcados (${phChecked.size})") }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(ph.toList(), key = { it.key }) { d ->
                        ListItem(
                            headlineContent = { Text(d.f.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("Foto" + (BrowserDetect.photoDim[d.key]?.let { " • $it" } ?: "") + " • " + Extract.host(d.f.url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(d.sel, { v -> BrowserDetect.togglePhoto(d.key, v) }); PhotoThumb(BrowserDetect.photoThumb[d.key] ?: d.f.url, d.f.page) } },
                            trailingContent = { TextButton({ BrowserDetect.sendPhotos(listOf(d), onSent) }) { Text("Baixar") } })
                    }
                }
            }
            // diagnóstico: link pequeno e separado no fim do painel (nunca no lugar das opções)
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            TextButton({
                val rep = listOf(Jobs.diagReport.value, BrowserDiag.report()).filter { it.isNotBlank() }.joinToString("\n")
                ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("diagnóstico", rep))
                android.widget.Toast.makeText(ctx, "Diagnóstico copiado (sem links). Cole no chat.", android.widget.Toast.LENGTH_SHORT).show()
            }, Modifier.align(Alignment.CenterHorizontally), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("Copiar diagnóstico (para suporte)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** URL de busca do próprio site para o termo: formulário/OpenSearch da página atual, senão ?s= / ?q= / /search?q=. */
fun siteSearchUrl(pageUrl: String, html: String?, term: String): String? {
    if (term.isBlank()) return null
    val reqs = if (html.isNullOrBlank()) emptyList() else SiteSearch.discoverRequests(pageUrl, html, term, includeGuesses = false)
    reqs.firstOrNull { it.method.equals("GET", true) && (it.source.startsWith("form") || it.source.startsWith("opensearch")) }?.let { return it.url }
    val origin = SiteSearch.originOf(pageUrl) ?: return null
    val q = SiteSearch.encode(term)
    val h = html.orEmpty()
    return when {
        h.contains("wp-content", true) || h.contains("wordpress", true) -> "$origin/?s=$q"
        h.contains("name=\"q\"", true) -> "$origin/search?q=$q"
        else -> "$origin/?s=$q"
    }
}

/** Escolha do que baixar: Só vídeos / Só fotos / Vídeos e fotos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaModeSelector(mode: Int, onChange: (Int) -> Unit) {
    val opts = listOf("Só vídeos", "Só fotos", "Vídeos e fotos")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        opts.forEachIndexed { i, l -> SegmentedButton(selected = mode == i, onClick = { onChange(i) }, shape = SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(l, maxLines = 1) } }
    }
}


private val thumbCache = android.util.LruCache<String, android.graphics.Bitmap>(80)
private val thumbGate = Semaphore(4)
/** Miniatura da foto na lista (baixa pequena, com o Referer da página). */
@Composable
fun PhotoThumb(url: String, page: String) {
    var bmp by remember(url) { mutableStateOf(thumbCache.get(url)) }
    LaunchedEffect(url) {
        if (bmp != null || !url.startsWith("http")) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) { try { thumbGate.withPermit {
            val rq = Request.Builder().url(url).header("Referer", page).header("Accept", "image/webp,image/jpeg,image/png,image/*;q=0.8,*/*;q=0.5").build()
            Net.browser.newCall(rq).execute().use { r -> val b = r.body?.bytes(); if (!r.isSuccessful || b == null) null else {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
                var ss = 1; while (maxOf(o.outWidth, o.outHeight) / (ss * 2) >= 160) ss *= 2
                android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss })
            } }
        } } catch (_: Throwable) { null } }?.also { thumbCache.put(url, it) }
    }
    val m = Modifier.size(64.dp).padding(start = 4.dp)
    val b = bmp
    if (b != null) androidx.compose.foundation.Image(b.asImageBitmap(), null, m.clip(MaterialTheme.shapes.small), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else Box(m, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Image, null) }
}
