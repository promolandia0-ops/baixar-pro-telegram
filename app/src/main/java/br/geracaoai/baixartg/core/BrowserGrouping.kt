package br.geracaoai.baixartg.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Agrupamento de vídeos por card da página no Navegador:
 * - Um item por card/vídeo (variantes de qualidade e playlists filhas colapsam no master/melhor);
 * - Descarta segmentos (.ts/.m4s/seg-N/chunk-N/init.mp4), previews/thumbnails animadas e links de navegação (/set/, /tag/, /user/).
 */
object BrowserGrouping {
    private const val CARD_SEL = "article,li,figure,[class*=card],[class*=item],[class*=video],[class*=post],[class*=thumb]"
    private val NON_VIDEO_LINK = Regex(
        """(?i)/(?:user|users|profile|profiles|author|authors|channel|channels|model|models|actor|actors|tag|tags|category|categories|cat|genre|genres|set|sets|album|albums|gallery|galleries|collection|collections|playlist|playlists|search|login|register|signup|auth|account|settings|help|about|contact|privacy|terms|dmca|comment|comments)(?:/|$|\?)"""
    )
    private val URL_IN_CARD = Regex(
        """https?://[^\s"'<>()\\]+?\.(?:mp4|webm|m3u8|mpd|mov|m4v|mkv|ts|m4s)(?:\?[^\s"'<>\\]*)?(?=$|[\s"'<>\\),;])""",
        RegexOption.IGNORE_CASE
    )

    fun isFilteredOutVideoUrl(url: String): Boolean {
        if (url.isBlank() || url.startsWith("blob:", true) || url.startsWith("data:", true)) return true
        if (Extract.isPhotoUrl(url) && !Extract.isVideoUrl(url)) return true
        if (TitleFix.isSubtitleUrl(url)) return true
        if (Extract.isSegmentUrl(url)) return true
        if (Extract.isPreviewVideo(url)) return true
        if (Extract.isVideoThumb(url)) return true
        if (MediaPick.isAdUrl(url)) return true
        return false
    }

    fun isNonVideoNavLink(url: String): Boolean {
        if (Extract.isVideoUrl(url) || Extract.looksLikeVideoPage(url)) return false
        return NON_VIDEO_LINK.containsMatchIn(url)
    }

    /** Extrai 1 item por card da página (suporta cards com <video>/HLS/variantes embutidas ou links de card). */
    fun extractCardVideos(pageUrl: String, html: String): List<Found> {
        val doc = Jsoup.parse(html, pageUrl)
        val allCards = doc.select(CARD_SEL)
        // só cards folha (que não contêm outros sub-cards com mídia/link de vídeo)
        val leafCards = allCards.filter { el ->
            el.select(CARD_SEL).none { child ->
                child !== el && (child.selectFirst("video,source,a[href]") != null || URL_IN_CARD.containsMatchIn(child.html()))
            }
        }
        if (leafCards.isEmpty()) {
            val parsed = Extract.parse(pageUrl, html).videos.filterNot { isFilteredOutVideoUrl(it.url) }
            return MediaIdentity.collapse(parsed)
        }

        val out = ArrayList<Found>()
        val seenPages = HashSet<String>()
        for (card in leafCards) {
            val cardTitle = TitleFix.pick(
                card.selectFirst("h1,h2,h3,h4,[class*=title]")?.text(),
                card.selectFirst("a[title]")?.attr("title"),
                card.selectFirst("img[alt]")?.attr("alt"),
                card.text().take(150),
            ).ifBlank { "vídeo" }
            val extra = card.text().replace(Regex("\\s+"), " ").trim().take(400)

            val rawUrls = LinkedHashSet<String>()
            card.select("video[src],video source[src],source[src],video[data-src],source[data-src],video[data-url],source[data-url]")
                .forEach { e ->
                    val u = e.attr("src").ifBlank { e.attr("data-src") }.ifBlank { e.attr("data-url") }
                    Extract.abs(pageUrl, u)?.let { rawUrls += it }
                }
            card.select("[data-video],[data-video-src],[data-stream],[data-file],[data-src][data-type*=video],[data-url]")
                .forEach { e ->
                    val u = e.attr("data-video").ifBlank { e.attr("data-video-src") }.ifBlank { e.attr("data-stream") }
                        .ifBlank { e.attr("data-file") }.ifBlank { e.attr("data-src") }.ifBlank { e.attr("data-url") }
                    Extract.abs(pageUrl, u)?.let { rawUrls += it }
                }
            card.select("a[href]").forEach { a ->
                val h = a.absUrl("href").substringBefore('#')
                if (h.startsWith("http") && Extract.mediaKindOf(h) != null) rawUrls += h
            }
            val cardRawHtml = card.html().replace("\\/", "/").replace("&amp;", "&")
            URL_IN_CARD.findAll(cardRawHtml).forEach { m ->
                Extract.abs(pageUrl, m.value)?.let { rawUrls += it }
            }

            val cardMedia = rawUrls
                .filter { !isFilteredOutVideoUrl(it) }
                .mapNotNull { u ->
                    val kind = Extract.mediaKindOf(u) ?: return@mapNotNull null
                    MediaIdentity.withQuality(Found(u, pageUrl, cardTitle, kind, ref = pageUrl, extra = extra))
                }

            val primaryLink = pickCardLink(card, pageUrl)
            if (cardMedia.isNotEmpty()) {
                val best = MediaIdentity.collapse(cardMedia).maxWithOrNull { a, b ->
                    MediaIdentity.score(a).compareTo(MediaIdentity.score(b))
                }
                if (best != null) {
                    val cardPage = primaryLink ?: pageUrl
                    out += best.copy(page = cardPage, ref = pageUrl)
                }
            } else if (primaryLink != null && seenPages.add(PageKey.norm(primaryLink))) {
                out += Found(primaryLink, primaryLink, cardTitle, Kind.SERVER, ref = pageUrl, extra = extra)
            }
        }
        return if (out.isNotEmpty()) MediaIdentity.collapse(out)
        else MediaIdentity.collapse(Extract.parse(pageUrl, html).videos.filterNot { isFilteredOutVideoUrl(it.url) })
    }

    private fun pickCardLink(card: Element, pageUrl: String): String? {
        val links = card.select("a[href]").mapNotNull { a ->
            val h = a.absUrl("href").substringBefore('#')
            if (!h.startsWith("http")) return@mapNotNull null
            if (h.trimEnd('/') == pageUrl.substringBefore('#').trimEnd('/')) return@mapNotNull null
            if (Extract.isPhotoUrl(h) && !Extract.isVideoUrl(h)) return@mapNotNull null
            if (isFilteredOutVideoUrl(h) || isNonVideoNavLink(h)) return@mapNotNull null
            val hasImg = a.selectFirst("img,picture,video") != null
            val isVidPage = Extract.looksLikeVideoPage(h) || Extract.isVideoUrl(h)
            Triple(h, hasImg, isVidPage)
        }
        return links.sortedWith(compareByDescending<Triple<String, Boolean, Boolean>> { it.third }.thenByDescending { it.second })
            .firstOrNull()?.first
    }

    fun collapseSelection(list: List<Found>): List<Found> = MediaIdentity.collapse(list)
}
