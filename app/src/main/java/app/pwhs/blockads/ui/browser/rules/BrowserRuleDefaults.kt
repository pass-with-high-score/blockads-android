package app.pwhs.blockads.ui.browser.rules

/**
 * Built-in baseline rules for the in-app browser.
 * Serves as the immutable fallback when no remote dynamic update has been applied.
 */
object BrowserRuleDefaults {

    const val INITIAL_VERSION = 1L

    val AD_HOST_SUFFIXES = listOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "adnxs.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "propellerclick.com",
        "adsterra.com",
        "exoclick.com",
        "ezoic.net",
        "ezoic.com",
        "mgid.com",
        "clickadu.com",
        "revenuehits.com",
        "bidvertiser.com",
        "hilltopads.net",
        "scorecardresearch.com",
        "zedo.com",
        "admob.com",
        "ads.youtube.com",
        "advertising.com",
        "rubiconproject.com",
        "pubmatic.com",
        "casalemedia.com",
        "openx.net",
        "adroll.com",
        "smartadserver.com",
        "moatads.com",
        "quantserve.com",
        "serving-sys.com",
        "fls-na.amazon.com",
        "fls-eu.amazon.com",
        // Regional Vietnamese ad networks
        "admicro.vn",
        "vcmedia.vn",
        "eclick.vn",
        "adtima.vn",
        "novanet.vn",
        // Popunder, 18+ and streaming ad networks (AdGuard filter sets)
        "adxcontent.com",
        "adxmedia.com",
        "vlit.site",
        "vlit.xyz",
        "exosrv.com",
        "tsyndicate.com",
        "tsyndication.com",
        "realsrv.com",
        "trafficjunky.com",
        "trafficstars.com",
        "ero-advertising.com",
        "juicyads.com",
        "hilltopads.com",
        "ad-maven.com",
        "adcash.com",
        "monetag.com",
        "yepads.com",
        "richpush.co",
        "richads.com",
        "clarium.io",
        // Fake video ads & ad network redirectors
        "clumsy-whereas.com",
        "ttwstatic.com",
        "bytedapm.com",
        // Vietnamese streaming ad network
        "adcenter.cx"
    )

    val GAMBLING_POPUNDER_KEYWORDS = listOf(
        "lu88", "hbet", "vu88", "man88", "k88.", "tx88", "du88", "x1bet",
        "bet88", "kubet", "shbet", "789bet", "okvip", "jun88", "hi88",
        "f8bet", "mb66", "123b", "fun88", "bk8", "rikvip", "cm88",
        "bom88", "vsbet", "78win", "gem88", "win79"
    )

    val AD_PATH_PATTERNS = listOf(
        "/ads.js",
        "/pagead/",
        "/doubleclick/",
        "/ad_status",
        "/get_midroll_info",
        "/api/stats/ads",
        "/youtubei/v1/player/ad_break",
        "googletagservices.com/tag/js/gpt.js",
        "/static/doubleclick/instream",
        "/popunder",
        "/popads",
        "/advertisement",
        "/adserver",
        "-adx.js",
        "/vl-top-adx",
        "/vl-main-adx",
        "/vl-underplayer-adx",
        "/vl-native-adx",
        "/catfish"
    )
}
