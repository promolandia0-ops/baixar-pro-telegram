package br.geracaoai.baixartg.core

import br.geracaoai.baixartg.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

/** Estado da aba “Buscar 4K”: texto livre, filtro de resolução, rolagem infinita e prefetch. */
object QualitySearch {
    enum class Filter { UHD, HD, ANY }

    val query = MutableStateFlow("")
    val link = MutableStateFlow("")
    val filter = MutableStateFlow(Filter.ANY)
    val items = MutableStateFlow<List<Found>>(emptyList())
    val loading = MutableStateFlow(false)
    val loadingMore = MutableStateFlow(false)
    val hasMore = MutableStateFlow(false)
    val pending = MutableStateFlow(false)
    val error = MutableStateFlow("")
    val status = MutableStateFlow("")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var prefetchJob: Job? = null
    private var page = 1
    private var prefetched: Downloader.QualityPage? = null
    private var lastQuery = ""
    private var lastLink = ""

    fun heightFromTitle(title: String): Int {
        val t = title
        return when {
            Regex("""\b(8k|4320p)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> 4320
            Regex("""\b(4k|2160p|uhd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> 2160
            Regex("""\b(1440p|qhd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) || Regex("""\b2k\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> 1440
            Regex("""\b(1080p|fhd|full\s*hd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> 1080
            Regex("""\b(720p|hd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> 720
            else -> Regex("""\b(480|360|240)p\b""", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }

    fun effectiveHeight(f: Found): Int = max(f.height, heightFromTitle(f.title))

    fun visible(): List<Found> {
        val all = items.value
        val want = wantHeight()
        if (want == 0) return all
        val hit = all.filter { effectiveHeight(it) >= want }
        return if (hit.isNotEmpty()) hit else all
    }

    fun filterFallback(): Boolean {
        val want = wantHeight()
        if (want == 0) return false
        val all = items.value
        return all.isNotEmpty() && all.none { effectiveHeight(it) >= want }
    }

    private fun wantHeight(): Int = when (filter.value) {
        Filter.UHD -> 2160
        Filter.HD -> 1080
        Filter.ANY -> 0
    }

    fun search() {
        val q = query.value.trim()
        val u = link.value.trim()
        if (q.isEmpty() && u.isEmpty()) { error.value = "Digite o que você quer buscar, ou cole um link."; return }
        job?.cancel(); prefetchJob?.cancel()
        page = 1; prefetched = null; lastQuery = q; lastLink = u
        items.value = emptyList(); error.value = ""; status.value = ""; hasMore.value = false; pending.value = false
        loading.value = true
        job = scope.launch(Dispatchers.IO) {
            val r = Downloader.qualitySearch(Prefs.server, q, u, 1)
            loading.value = false
            if (!r.ok) { error.value = r.error; status.value = r.error; pending.value = false; return@launch }
            items.value = r.results
            pending.value = r.pending
            hasMore.value = r.hasMore || r.pending
            page = r.page
            status.value = statusFor(r)
            if (r.pending) pollPending()
            else if (r.hasMore) prefetch(2)
        }
    }

    fun loadMore() {
        if (loading.value || loadingMore.value || !hasMore.value || pending.value) return
        val next = page + 1
        val ready = prefetched
        if (ready != null && ready.page == next) {
            prefetched = null
            absorb(ready)
            if (hasMore.value) prefetch(next + 1)
            return
        }
        loadingMore.value = true
        job = scope.launch(Dispatchers.IO) {
            val r = Downloader.qualitySearch(Prefs.server, lastQuery, lastLink, next)
            loadingMore.value = false
            absorb(r)
            if (hasMore.value && !pending.value) prefetch(next + 1)
        }
    }

    private fun pollPending() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            repeat(16) {
                delay(700)
                val r = Downloader.qualitySearch(Prefs.server, lastQuery, lastLink, 1)
                if (!r.ok) return@repeat
                absorb(r)
                pending.value = r.pending
                hasMore.value = r.hasMore || r.pending
                if (!r.pending) {
                    if (r.hasMore) prefetch(2)
                    return@launch
                }
            }
            pending.value = false
        }
    }

    private fun prefetch(p: Int) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            val r = Downloader.qualitySearch(Prefs.server, lastQuery, lastLink, p)
            if (r.ok) prefetched = r
        }
    }

    private fun absorb(r: Downloader.QualityPage) {
        if (!r.ok) { if (items.value.isEmpty()) error.value = r.error; return }
        val have = items.value.map { it.url }.toHashSet()
        val add = r.results.filter { it.url !in have }
        if (add.isNotEmpty()) items.value = (items.value + add).sortedWith(compareByDescending<Found> { effectiveHeight(it) }.thenByDescending { it.duration })
        pending.value = r.pending
        hasMore.value = r.hasMore || r.pending
        if (!r.pending) page = r.page
        status.value = statusFor(r, items.value.size)
    }

    private fun statusFor(r: Downloader.QualityPage, n: Int = r.results.size): String {
        val extra = when {
            r.pending -> " Buscando mais…"
            filterFallback() -> " Nenhum com essa qualidade confirmada; mostrando o melhor que achei."
            else -> ""
        }
        return if (n <= 0 && r.pending) "Buscando…" else if (n <= 0) "Nenhum vídeo encontrado." else "$n vídeo(s).$extra"
    }

    fun enqueue(list: List<Found>, start: Boolean): Boolean {
        if (list.isEmpty()) return false
        return Jobs.addExternal(list.map { it.copy(kind = Kind.SERVER, bestQuality = true) to it.size.coerceAtLeast(-1) }, start, label = "4K")
    }
}
