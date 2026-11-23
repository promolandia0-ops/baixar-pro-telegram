package br.geracaoai.baixartg.core

import java.net.URI

/**
 * Escolhe o stream principal e descarta anúncio pré-roll (VAST/IMA e redes conhecidas).
 * Nunca pega o primeiro capturado só porque chegou antes: fica o maior/mais longo.
 */
object MediaPick {
    const val SHORT_MS = 60_000L
    const val SHORT_BYTES = 3L * 1024 * 1024
    const val WAIT_FOR_MAIN_MS = 10_000L

    data class Cand(
        val url: String,
        val bytes: Long = -1,
        val durationMs: Long = -1,
        val pageUrl: String = "",
    )

    private val AD_HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "googletagservices.com", "googletagmanager.com",
        "imasdk.googleapis.com", "2mdn.net", "serving-sys.com", "adform.net", "moatads.com",
        "scorecardresearch.com", "advertising.com", "adnxs.com", "adsrvr.org", "spotxchange.com",
        "stickyadstv.com", "exoclick.com", "exosrv.com", "realsrv.com", "juicyads.com",
        "trafficjunky.net", "trafficjunky.com", "hilltopads.net", "clickadu.com", "adcash.com",
        "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "admaven.com",
        "monetag.com", "tsyndicate.com", "trafficstars.com", "zeropark.com", "mgid.com",
        "revcontent.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
        "pubmatic.com", "rubiconproject.com", "openx.net", "ad-delivery.net", "adsafeprotected.com",
        "serving-system.com", "mtgglobals.com", "ad.gt", "bidswitch.net",
    )

    private val AD_URL = Regex(
        """(?i)(?:[/?&._-](?:vast|vpaid|vmap|preroll|midroll|ima(?:sdk|3)?|adbreak|adsystem|adtag|adunit|companionad)(?:[/?&.=_-]|$)|/pagead/|/adsense/|doubleclick|googlesyndication|imasdk|exoclick|trafficjunky|juicyads|/gampad/|adserver|ad-delivery)"""
    )

    fun hostOf(url: String): String = try {
        URI(url.trim()).host?.lowercase()?.removePrefix("www.")?.trimEnd('.') ?: ""
    } catch (_: Exception) { "" }

    fun base(host: String): String {
        val h = host.lowercase().removePrefix("www.")
        val p = h.split('.')
        return if (p.size >= 2) p.takeLast(2).joinToString(".") else h
    }

    fun isAdHost(host0: String): Boolean {
        val h = host0.lowercase().removePrefix("www.").trimEnd('.')
        if (h.isBlank()) return false
        var cur = h
        while (true) {
            if (cur in AD_HOSTS) return true
            val i = cur.indexOf('.')
            if (i < 0) return false
            cur = cur.substring(i + 1)
            if (!cur.contains('.')) return cur in AD_HOSTS
        }
    }

    fun isAdUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (isAdHost(hostOf(url))) return true
        return AD_URL.containsMatchIn(url)
    }

    /** Anúncio curto ou de rede de ads. Vídeo real em CDN de outro domínio NÃO entra só por ser de outro host. */
    fun isLikelyAd(c: Cand): Boolean {
        if (isAdUrl(c.url)) return true
        if (c.durationMs in 1 until SHORT_MS) return true
        return false
    }

    fun score(c: Cand): Long {
        val dur = if (c.durationMs > 0) c.durationMs else 0L
        val bytes = if (c.bytes > 0) c.bytes else 0L
        return dur * 1_000_000L + bytes
    }

    /** Melhor candidato: ignora anúncios se houver um principal; senão o maior que restar. */
    fun pick(cands: List<Cand>): Cand? {
        if (cands.isEmpty()) return null
        val unique = cands.distinctBy { it.url.substringBefore('#') }
        val main = unique.filter { !isLikelyAd(it) }
        val pool = main.ifEmpty { unique.filter { !isAdUrl(it.url) } }.ifEmpty { unique }
        return pool.maxByOrNull { score(it) }
    }

    /**
     * Só fecha a captura quando já há um stream principal, ou acabou o tempo de espera
     * (se só chegou anúncio curto, espera o vídeo de verdade).
     */
    fun readyToPick(cands: List<Cand>, waitedMs: Long, minWaitIfOnlyAds: Long = WAIT_FOR_MAIN_MS): Boolean {
        if (cands.any { !isLikelyAd(it) }) return true
        return waitedMs >= minWaitIfOnlyAds && cands.isNotEmpty()
    }
}
