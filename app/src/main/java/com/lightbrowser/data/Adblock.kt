package com.lightbrowser.data

/**
 * Host-based ad/tracker blocker v2. Exact host or any subdomain matches.
 * Toggle globally (Prefs.adBlock) or per-site (SitePrefs).
 */
object Adblock {
    private val HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagmanager.com", "googletagservices.com", "google-analytics.com",
        "analytics.google.com", "stats.g.doubleclick.net", "adservice.google.com",
        "facebook.net", "connect.facebook.net", "ads.facebook.com",
        "amazon-adsystem.com", "aax.amazon-adsystem.com", "adsrvr.org",
        "ads.yahoo.com", "advertising.com", "taboola.com", "outbrain.com",
        "criteo.com", "criteo.net", "rubiconproject.com", "pubmatic.com",
        "moatads.com", "scorecardresearch.com", "quantserve.com", "hotjar.com",
        "fullstory.com", "segment.io", "cdn.segment.com", "mixpanel.com",
        "optimizely.com", "newrelic.com", "nr-data.net", "beacon.gutefrage.net",
        "adskeeper.com", "mgid.com", "revcontent.com", "adnxs.com",
        "ads-twitter.com", "static.ads-twitter.com", "ads.linkedin.com",
        "ads.pinterest.com", "ads.tiktok.com", "snap.licdn.com", "bat.bing.com",
        "clarity.ms", "c.bing.com", "ads.samsungads.com", "samsungads.com",
        "unityads.unity3d.com", "applovin.com", "ironsrc.com", "mopub.com",
        "inmobi.com", "smrtb.com", "bidswitch.net", "springserve.com",
        "sharethrough.com", "triplelift.com", "openx.net", "mathtag.com",
        "demdex.net", "everesttech.net", "agkn.com", "addthis.com",
        "chartbeat.com", "crazyegg.com", "inspectlet.com", "mouseflow.com",
        "luckyorange.com", "clicktale.net", "contentsquare.net", "quantummetric.com",
        "usertesting.com", "survicate.com",
        "popads.net", "popcash.net", "adcash.com", "exoclick.com",
        "trafficjunky.net", "juicyads.com", "adsterra.com", "propellerads.com",
        "hilltopads.net", "admaven.com", "clickadu.com", "zeropark.com",
        "voluum.com", "redintelligence.net", "fwmrm.net", "stickyadstv.com",
        "g.doubleclick.net", "securepubads.g.doubleclick.net", "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com", "googleads.g.doubleclick.net",
        "fundingchoicesmessages.google.com", "consent.google.com",
        "imasdk.googleapis.com", "jsecoin.com", "coinimp.com", "coinhive.com",
        "2mdn.net", "static.cloudflareinsights.com"
    )
    private val PATH_RULES = listOf(
        "cdn-cgi/", "/ads/", "/adserver", "/pagead/", "doubleclick", "googlesyndication"
    )

    fun isAd(host: String): Boolean {
        if (host.isBlank()) return false
        val h = host.lowercase().trim()
        return HOSTS.any { rule -> h == rule || h.endsWith(".$rule") }
    }

    /** Full-URL check: host rules + path rules (fixes dead ajax.cloudflare.com/cdn-cgi case). */
    fun isAdUrl(url: String): Boolean {
        return try {
            val u = android.net.Uri.parse(url)
            val h = (u.host ?: "").lowercase()
            if (isAd(h)) return true
            val path = ((u.path ?: "") + "?" + (u.query ?: "")).lowercase()
            PATH_RULES.any { path.contains(it) }
        } catch (_: Exception) { false }
    }
}
