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
        if (t.isNotBlank()) { val gone = photos.filter { !matches(it.f, t) }; if (gone.isNotEmpty()) { photos.removeAll(gone.toSet()); gone.forEach { d -> d.keys.forEach { photoKeys.remove(it) } }; photoPosts.clear() } }
        else photoPosts.clear()
        for (i in photos.indices) { val d = photos[i]; val v = matches(d.f, t); if (d.sel != v) photos[i] = d.copy(sel = v) }
    }

    // ---------------- Fotos (opcional, separado dos vídeos) ----------------
    val photos = mutableStateListOf<Det>()
    val photosOn = mutableStateOf(Prefs.mediaMode != 0)   // "Só fotos" e "Vídeos e fotos" detectam fotos (inclusive ao reabrir o app)
    /** 0 Só vídeos, 1 Só fotos, 2 Vídeos e fotos. "Só fotos" só esconde os vídeos (continuam detectados/salvos). */
    val mode = mutableIntStateOf(Prefs.mediaMode)
    fun setMode(m: Int) { Prefs.mediaMode = m; mode.intValue = m; setPhotosOn(m != 0) }
    private val photoKeys = HashSet<String>()
    private val thumbKeys = java.util.Collections.synchronizedSet(HashSet<String>())
    fun setPhotosOn(v: Boolean) { photosOn.value = v; if (!v) { photos.clear(); photoKeys.clear(); photoPosts.clear() } }
    fun togglePhoto(key: String, v: Boolean) { val i = photos.indexOfFirst { it.key == key }; if (i >= 0) photos[i] = photos[i].copy(sel = v) }
    fun setAllPhotos(v: Boolean) { for (i in photos.indices) if (photos[i].sel != v) photos[i] = photos[i].copy(sel = v) }

    private fun videoTokens(): HashSet<String> {
        val vt = HashSet<String>()
        list.forEach { vt += Photos.idTokens(it.f.url); vt += Photos.idTokens(it.f.page) }
        hidden.values.forEach { vt += Photos.idTokens(it.f.page) }
        synchronized(cardPages) { cardPages.forEach { vt += Photos.idTokens(it) } }
        return vt
    }
    /** Foto passa no nome (mesma regra estrita dos vídeos: legenda/título/alt/título do post). Sem nome: passa. */
    private fun photoMatches(f: Found) = term.value.isBlank() || matches(f)

    /** Adiciona fotos já conferidas (tamanho real ≥ 500 px). Só thread principal. */
    private fun addPhotos(page: String, ps: List<Photos.Photo>, checkThumbs: Boolean) {
        val vt = videoTokens()
        for (p in ps) {
            if (br.geracaoai.baixartg.web.AdBlock.isAdHost(Extract.host(p.url))) continue
            val k = Photos.key(p.url)
            if (k in photoKeys || (checkThumbs && k in thumbKeys) || Photos.sharesId(p.url, vt)) continue
            val f = Found(p.url, page, Photos.title(p), Kind.PHOTO, ref = page, extra = p.text)
            if (!photoMatches(f)) continue   // com nome: só fotos cuja legenda/título bate (não grava a chave: volta se o nome mudar)
            photoKeys += k
            if (Sent.isQueued(listOf(k)) || Sent.anySent(listOf(k)) || Sent.isSent(f)) continue
            photos += Det("ph:$k", f, sel = term.value.isNotBlank(), keys = listOf(k))
        }
    }
    private val photoPosts = java.util.Collections.synchronizedSet(HashSet<String>())
    private val photoGate = Semaphore(3)
    /** Cards de post de foto: abre a página do post (como o resolvedor de vídeo) e pega as fotos grandes de lá. */
    fun onPhotoCards(page: String, cards: List<Photos.Card>) {
        if (!photosOn.value || cards.isEmpty()) return
        val words = NameFilter.words(term.value)
        for (c in cards) {
            val np = normPage(c.href)
            if (np in cardPages || synchronized(seenCards) { c.href in seenCards } || list.any { normPage(it.f.page) == np }) continue   // é card de vídeo
            if (!photoPosts.add(np)) continue
            scope.launch {
                photoGate.withPermit {
                    val ps = try { Photos.fromPost(c.href, c.text) { u -> photoRender.withPermit { JsRender.render(u, 15_000)?.html } } } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() }
                    val ok = if (words.isEmpty()) ps else ps.filter { NameFilter.matchesText(it.text, words) }
                    if (ps.isNotEmpty() && ok.isEmpty()) photoPosts.remove(np)   // nome diferente: pode voltar se mudar a busca
                    if (ok.isNotEmpty()) withContext(Dispatchers.Main) { addPhotos(c.href, ok, checkThumbs = false) }
                }
            }
        }
    }

    /** Fotos coletadas do DOM (Photos.JS). Nunca mexe na lista de vídeos. Só thread principal. */
    fun onPhotos(page: String, ps0: List<Photos.Photo>) {
        if (!photosOn.value || ps0.isEmpty()) return
        // tamanho desconhecido (lazy/placeholder): confere no arquivo de verdade antes de listar
        val unknown = ps0.filter { it.w <= 0 || it.h <= 0 }.filter { Photos.key(it.url) !in photoKeys }
        if (unknown.isNotEmpty()) scope.launch {
            val ok = ArrayList<Photos.Photo>()
            for (p in unknown.take(60)) { val wh = Photos.probeSize(p.url, page) ?: continue; if (minOf(wh.first, wh.second) >= Photos.MIN_SIDE) ok += p.copy(w = wh.first, h = wh.second) }
            if (ok.isNotEmpty()) withContext(Dispatchers.Main) { addPhotos(page, ok, checkThumbs = true) }
        }
        val ps = ps0.filter { it.w > 0 && it.h > 0 }
        if (ps.isEmpty()) return
        addPhotos(page, ps, checkThumbs = true)
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
        todo.forEach { d -> Sent.markQueued(d.keys + ("pg:" + Sent.norm(d.f.url))) }
        Pipeline.add(todo.map { d -> Pipeline.In(d.f.page, d.f.title, d.f.extra, d.keys, d.f, photo = true) })
        onMsg("Enviando ${todo.size} foto(s). Acompanhe na Fila.")
    }
    fun toggle(key: String, v: Boolean) { val i = list.indexOfFirst { it.key == key }; if (i >= 0 && !list[i].sent) list[i] = list[i].copy(sel = v) }
    val pending = mutableIntStateOf(0)
    /** Cards desta página que já estão na fila ou já foram enviados (não são abertos de novo). */
    val alreadyHere = mutableIntStateOf(0)
    /** Quando a página atual começou a abrir (para o "Procurando…" ter fim). */
    val pageAt = androidx.compose.runtime.mutableLongStateOf(0L)
    fun newPage() { alreadyHere.intValue = 0; pageAt.longValue = System.currentTimeMillis() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(3)
    /** Posts de foto que precisam de WebView: no máximo 1 por vez, para não tirar vagas dos vídeos. */
    private val photoRender = Semaphore(1)
    private val seenCards = HashSet<String>()
    private val keys = HashMap<String, String>()

    fun reset() {
        loadedHosts.clear(); relaunched = 0
        synchronized(seenCards) {
            seenCards.clear(); keys.clear()
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
        scope.launch(Dispatchers.Main) {
            if (synchronized(seenCards) { pk.any { keys.containsKey(it) } }) return@launch
            val k = "card:" + normPage(card)
            if (blocked(f, pk)) { synchronized(seenCards) { pk.forEach { keys[it] = "x:$k" } }; return@launch }
            synchronized(seenCards) { pk.forEach { keys[it] = k }; keys[k] = k }
            list += Det(k, f, sel = matches(f), keys = pk, state = 1)
        }
    }
    private fun markFailed(card: String) = scope.launch(Dispatchers.Main) {
        val hk = "card:" + normPage(card)
        hidden[hk]?.let { if (it.state == 1) hidden[hk] = it.copy(state = 2) }
        val i = list.indexOfFirst { it.key == "card:" + normPage(card) && it.state == 1 }
        if (i >= 0) list[i] = list[i].copy(state = 2, sel = false)
    }

    private fun isRealVideo(f: Found): Boolean =
        f.kind != Kind.PHOTO && !(Extract.isPhotoUrl(f.url) && !Extract.isVideoUrl(f.url)) && !Extract.isVideoThumb(f.url) &&
            (f.kind == Kind.SERVER || f.kind == Kind.HLS || Extract.isVideoUrl(f.url) || f.kind == Kind.DIRECT)

    /** Adiciona (ou troca por qualidade maior) — dedupe por identidade do vídeo. */
    private fun publish(f0: Found, extraKeys: List<String> = emptyList()) {
        val f = MediaIdentity.withQuality(f0)
        if (!isRealVideo(f) || TitleFix.isSubtitleUrl(f.url)) return
        val ks = (extraKeys + dedupeKeys(f)).distinct()
        scope.launch(Dispatchers.Main) {
            val hitKey = synchronized(seenCards) { ks.firstNotNullOfOrNull { keys[it] } }
            if (hitKey != null) {
                // item que o usuário já mandou baixar enquanto ainda carregava: guarda o vídeo resolvido para enviar
                hidden[hitKey]?.let { h ->
                    if (h.state != 0 || MediaIdentity.isBetter(f, h.f)) {
                        val nf = if (h.state != 0 && !TitleFix.isJunk(h.f.title) && TitleFix.isJunk(f.title)) f.copy(title = h.f.title) else f
                        hidden[hitKey] = h.copy(f = nf, state = 0, keys = (h.keys + ks.filter { it.startsWith("ip:") || it.startsWith("p:") }).distinct())
                    }
                    synchronized(seenCards) { ks.forEach { keys[it] = hitKey } }
                    return@launch
                }
                val i = list.indexOfFirst { it.key == hitKey }
                if (i >= 0) {
                    val better = list[i].state != 0 || MediaIdentity.isBetter(f, list[i].f)
                    val sent = list[i].sent || Sent.isSent(f) || Sent.hasCanonical(f)
                    val nf = if (better) f else list[i].f
                    val pk = (list[i].keys + ks.filter { it.startsWith("ip:") || it.startsWith("p:") }).distinct()
                    val sent2 = sent || Jobs.isQueuedOrSent(nf, pk)
                    val keepTitle = if (list[i].state != 0 && !TitleFix.isJunk(list[i].f.title) && TitleFix.isJunk(nf.title)) nf.copy(title = list[i].f.title) else nf
                    if (sent2) list.removeAt(i)
                    else list[i] = list[i].copy(f = keepTitle, sel = list[i].sel, keys = pk, state = 0)
                }
                synchronized(seenCards) { ks.forEach { keys[it] = hitKey } }
                return@launch
            }
            val k = ks.first()
            val pk = ks.filter { it.startsWith("ip:") || it.startsWith("p:") }
            // já mandado baixar/enviado (nesta ou em outra sessão, mesmo com outra URL): nunca reaparece
            if (blocked(f, ks)) { synchronized(seenCards) { ks.forEach { keys[it] = "x:$k" } }; return@launch }
            synchronized(seenCards) { ks.forEach { keys[it] = k } }
            list += Det(k, f, sel = matches(f), keys = pk)
        }
    }

    private val QUALITY_TOK = Regex("""(?i)(?:[_.\-/]|^)(?:\d{3,4}p\d{0,2}|[1-8]k|uhd|fhd|hd|sd|ld|hq|lq|low|high|medium|mobile|source|original|\d{3,4}x\d{3,4}|\d{3,5}k)(?=$|[_.\-/])""")
    private val GENERIC_STEM = setOf("index", "master", "playlist", "video", "media", "manifest", "stream", "chunklist", "file", "download", "embed", "play", "watch", "")

    private fun normPage(u: String) = u.substringBefore('#').substringBefore('?').lowercase().replace("://www.", "://").replace("://m.", "://").trimEnd('/')
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
                    if (d.state == 1 && relaunched < 60) { relaunched++; resolve(d.f.page, d.f.title, d.f.page, d.f.extra) }
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
            (!(k.startsWith("ip:") || k.startsWith("p:")) || k.substringAfter(':') in cardPages || d.f.kind == Kind.SERVER && k.substringAfter(':') == normPage(d.f.url)) } + Sent.keysOf(d.f) + ("pg:" + Sent.norm(d.f.url)) +
            (if (d.f.page.isNotBlank() && d.key.startsWith("card:")) listOf("pg:" + Sent.norm(d.f.page)) else emptyList())).distinct()

    private fun blocked(f: Found, ks: List<String>): Boolean {
        val all = ks + Sent.keysOf(f) + ("pg:" + Sent.norm(f.url)) + (if (f.page != browsing) listOf("pg:" + Sent.norm(f.page)) else emptyList())
        return Sent.isQueued(all) || Sent.anySent(all) || Jobs.isQueuedOrSent(f, ks.filter { it.startsWith("ip:") || it.startsWith("p:") })
    }

    /** Some da lista na hora, grava como "já pedido" e entra na fila (se houver envio rodando, entra assim que ele terminar). */
    private fun dispatch(ds: List<Det>) {
        if (ds.isEmpty()) return
        ds.forEach { d -> val ak = allKeys(d); Sent.markQueued(ak); synchronized(seenCards) { ak.forEach { k -> if (!keys.containsKey(k)) keys[k] = "x:" + d.key } } }
        // fila persistente: resolvidos vão direto para baixar/enviar; os "carregando…" a fila abre a página sozinha
        Pipeline.add(ds.map { d ->
            if (d.state == 0) Pipeline.In(d.f.page.ifBlank { d.f.url }, d.f.title, d.f.extra, d.keys, d.f)
            else Pipeline.In(d.f.page.ifBlank { d.f.url }, d.f.title, d.f.extra, d.keys, null)
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

    fun setAll(v: Boolean) { for (i in list.indices) { val d = list[i]; val nv = v && !d.sent && d.state != 2; if (d.sel != nv) list[i] = d.copy(sel = nv) } }

    /** Mídia interceptada pelo navegador (pedido de rede de vídeo). */
    fun intercepted(url: String, page: String, title: String) {
        if (page.isNotBlank()) browsing = page
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
        pageInfo[normPage(page)] = c.bestPageTitle to c.pageText
        // mídias já listadas desta página ganham o título real (antes podiam ter vindo como "arquivo"/idioma)
        val pt = c.bestPageTitle
        if (pt.isNotBlank()) scope.launch(Dispatchers.Main) {
            for (i in list.indices) { val d = list[i]
                if (normPage(d.f.page) == normPage(page) && (TitleFix.isJunk(d.f.title) || d.f.title == Extract.fileName(d.f.url))) {
                    val nf = d.f.copy(title = pt, extra = listOf(pt, c.pageText, d.f.extra).joinToString(" ").trim())
                    list[i] = d.copy(f = nf, sel = !d.sent && matches(nf))
                }
            }
        }
        val root = Extract.host(page)
        for (v in c.videos) if (!v.startsWith("blob:")) intercepted(v, page, "")
        for (a in c.anchors) if (a.thumb.isNotBlank()) thumbKeys += Photos.key(a.thumb)
        for (a in c.anchors) {
            if (a.nav) continue
            if (a.thumb.isBlank() && !Extract.looksLikeVideoPage(a.href) && !DURATION.containsMatchIn(a.ctx)) continue
            val href = a.href.substringBefore('#')
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
                val js = JsRender.listing(nx, 30_000)
                js?.collected?.let { col -> withContext(Dispatchers.Main) { onCollected(nx, col, background = true) } }
                // fotos também nas próximas páginas da busca ("Só fotos" / "Vídeos e fotos")
                val h = js?.html
                if (photosOn.value && h != null) {
                    val ps = Photos.fromHtml(nx, h); val cs = Photos.cardsFromHtml(nx, h)
                    withContext(Dispatchers.Main) { onPhotos(nx, ps); onPhotoCards(nx, cs) }
                }
            }
        }
    }

    /** Baixar / Baixar marcados: resolve antes os que ainda estão carregando, depois envia (uma vez só). */
    fun sendAsync(ds: List<Det>, onMsg: (String) -> Unit) {
        scope.launch(Dispatchers.Main) {
            val want = ds.map { it.key }.toHashSet()
            // pega a versão atual de cada item (pode ter acabado de carregar)
            val cur = list.filter { it.key in want && !it.sent && it.state != 2 }
            if (cur.isEmpty()) { onMsg("Nada novo para enviar."); return@launch }
            val curKeys = cur.map { it.key }.toHashSet()
            list.removeAll { it.key in curKeys }   // somem da lista na hora
            // os que ainda carregavam param aqui: a fila abre cada página sozinha, sem esperar os outros
            cur.filter { it.state != 0 }.forEach { d -> jobs.remove(normPage(d.f.page))?.cancel() }
            dispatch(cur)
            onMsg("${cur.size} vídeo(s) na fila de envio. Vão chegando no Telegram um por um (veja na Fila).")
        }
    }

    private fun resolve(card: String, title: String, page: String, cardCtx: String = "") {
        // Causa do "Procurando vídeos…" sem fim: cards que já estavam na fila/enviados eram abertos de novo
        // (página + WebView com play + servidor), ocupando as 3 vagas do navegador por muito tempo; os novos
        // ficavam esperando atrás deles e a lista não aparecia. Agora já na fila/enviado = pula na hora.
        if (!Extract.isVideoUrl(card)) {
            val pk = listOf("ip:" + normPage(card), "p:" + normPage(card))
            val f = Found(card, card, "vídeo", Kind.SERVER)
            val skip = try { blocked(f, pk) } catch (_: Exception) { false }
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
                            supportingContent = { Text(when (d.state) { 1 -> "carregando…"; 2 -> "não carregou"; else -> d.f.typeLabel + " • " + (if (d.f.kind == Kind.SERVER) "via servidor" else "pronto") } + if (d.sent) " • Já enviado" else "") },
                            leadingContent = { Checkbox(d.sel && !d.sent, { v -> BrowserDetect.toggle(d.key, v) }, enabled = !d.sent) },
                            trailingContent = {
                                TextButton({ BrowserDetect.sendAsync(listOf(d), onSent) }, enabled = !d.sent && d.state != 2) { Text("Baixar") }
                            })
                    }
                }
            } else {
                val ph = BrowserDetect.photos
                val phChecked = ph.filter { it.sel }
                Text("${phChecked.size} de ${ph.size} fotos marcadas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                if (ph.isEmpty()) Text("Role a página: as fotos grandes postadas (não capas de vídeo) aparecem aqui.", style = MaterialTheme.typography.bodySmall)
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
                            supportingContent = { Text("Foto • " + Extract.fileName(d.f.url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Checkbox(d.sel, { v -> BrowserDetect.togglePhoto(d.key, v) }) },
                            trailingContent = { TextButton({ BrowserDetect.sendPhotos(listOf(d), onSent) }) { Text("Baixar") } })
                    }
                }
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
