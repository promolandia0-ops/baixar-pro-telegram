package br.geracaoai.baixartg.core

import br.geracaoai.baixartg.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
    val error = MutableStateFlow("")
    val status = MutableStateFlow("")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var prefetchJob: Job? = null
    private var page = 1
    private var prefetched: Downloader.QualityPage? = null
    private var lastQuery = ""
    private var lastLink = ""

    fun visible(): List<Found> {
        val all = items.value
        return when (filter.value) {
            Filter.UHD -> all.filter { it.height >= 2160 }
            Filter.HD -> all.filter { it.height >= 1080 }
            Filter.ANY -> all
        }
    }

    fun search() {
        val q = query.value.trim()
        val u = link.value.trim()
        if (q.isEmpty() && u.isEmpty()) { error.value = "Digite o que você quer buscar, ou cole um link."; return }
        job?.cancel(); prefetchJob?.cancel()
        page = 1; prefetched = null; lastQuery = q; lastLink = u
        items.value = emptyList(); error.value = ""; status.value = ""; hasMore.value = false
        loading.value = true
        job = scope.launch(Dispatchers.IO) {
            val r = Downloader.qualitySearch(Prefs.server, q, u, 1)
            loading.value = false
            if (!r.ok) { error.value = r.error; status.value = r.error; return@launch }
            items.value = r.results
            hasMore.value = r.hasMore
            page = r.page
            status.value = if (r.results.isEmpty()) "Nenhum vídeo encontrado." else "${r.results.size} vídeo(s). A melhor qualidade aparece primeiro."
            if (r.hasMore) prefetch(2)
        }
    }

    fun loadMore() {
        if (loading.value || loadingMore.value || !hasMore.value) return
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
            if (hasMore.value) prefetch(next + 1)
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
        if (add.isNotEmpty()) items.value = (items.value + add).sortedWith(compareByDescending<Found> { it.height }.thenByDescending { it.duration })
        hasMore.value = r.hasMore
        page = r.page
        status.value = "${items.value.size} vídeo(s)."
    }

    fun enqueue(list: List<Found>, start: Boolean): Boolean {
        if (list.isEmpty()) return false
        return Jobs.addExternal(list.map { it.copy(kind = Kind.SERVER, bestQuality = true) to it.size.coerceAtLeast(-1) }, start, label = "4K")
    }
}
