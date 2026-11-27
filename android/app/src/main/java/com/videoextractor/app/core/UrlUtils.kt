package com.videoextractor.app.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Share-text extraction, host validation, VIP/DRM blocklist, and short-link expansion.
 * Mirrors the Python backend rules:
 * - Preserves percent-encoded paths and signed query parameters.
 * - Matches platform hosts strictly on domain-label boundaries.
 * - Normalizes trailing DNS root dots.
 */
object UrlUtils {
    private val URL_RE = Regex("https?://[^\\s<>\"'\\u3000-\\u303F\\uFF00-\\uFFEF]+", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCT = ".,;:!?)]}'\">」』】）》，。！？；："

    private val DOUYIN_ID_RE = Regex("/(?:video|note|slides|share/video)/(\\d{10,25})")
    private val KUAISHOU_ID_RE = Regex("/(?:short-video|photo|fw/photo)/([A-Za-z0-9_-]{6,32})")
    private val XIANYU_ID_RE = Regex("(?:[?&]id=|/item/)(\\d{6,20})")
    private val XHS_ID_RE = Regex("/(?:explore|discovery/item|item)/([A-Za-z0-9]{16,32})")
    private val BV_ID_RE = Regex("\\b(BV[0-9A-Za-z]{10})\\b")
    private val AV_ID_RE = Regex("\\bav(\\d{3,15})\\b", RegexOption.IGNORE_CASE)
    private val YT_ID_RE = Regex("(?:youtu\\.be/|youtube\\.com/(?:shorts/|embed/|live/|v/))([A-Za-z0-9_-]{11})")
    private val TWEET_ID_RE = Regex("/status(?:es)?/(\\d{5,25})")
    private val IG_CODE_RE = Regex("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,24})")
    private val VIMEO_ID_RE = Regex("vimeo\\.com/(?:video/|channels/[^/]+/|groups/[^/]+/videos/|manage/videos/)?(\\d{5,15})")
    private val TB_JS_URL_RE = Regex("""var\s+url\s*=\s*['"](https?://[^'"]+)['"]""", RegexOption.IGNORE_CASE)

    val SHORT_LINK_HOSTS = setOf(
        "v.douyin.com",
        "v.kuaishou.com",
        "v.chenzhongtech.com",
        "b23.tv",
        "xhslink.com",
        "t.cn",
        "dwz.cn",
        "m.tb.cn",
        "vm.tiktok.com",
        "vt.tiktok.com",
        "youtu.be",
        "t.co",
        "fb.watch",
        "redd.it",
        "v.redd.it",
        "instagr.am",
    )

    private val BLOCKED_HOSTS = linkedMapOf(
        "v.qq.com" to "腾讯视频",
        "film.qq.com" to "腾讯视频",
        "wetv.vip" to "腾讯视频",
        "wetv.video" to "腾讯视频",
        "iqiyi.com" to "爱奇艺",
        "iq.com" to "爱奇艺",
        "youku.com" to "优酷",
        "soku.com" to "优酷",
        "mgtv.com" to "芒果TV",
        "hunantv.com" to "芒果TV",
        "netflix.com" to "Netflix",
        "disneyplus.com" to "Disney+",
        "disney.com" to "Disney+",
        "hbo.com" to "HBO",
        "hbomax.com" to "HBO",
        "max.com" to "HBO",
        "primevideo.com" to "Prime Video",
        "hulu.com" to "Hulu",
        "spotify.com" to "Spotify",
        "tv.apple.com" to "Apple TV+",
        "paramountplus.com" to "Paramount+",
        "peacocktv.com" to "Peacock",
        "crunchyroll.com" to "Crunchyroll",
        "channels.weixin.qq.com" to "微信视频号",
        "finder.video.qq.com" to "微信视频号",
    )

    private val PLATFORM_HINTS = listOf(
        "douyin.com" to "抖音",
        "iesdouyin.com" to "抖音",
        "tiktok.com" to "TikTok",
        "bilibili.com" to "B站",
        "b23.tv" to "B站",
        "kuaishou.com" to "快手",
        "chenzhongtech.com" to "快手",
        "xiaohongshu.com" to "小红书",
        "xhslink.com" to "小红书",
        "goofish.com" to "闲鱼",
        "2.taobao.com" to "闲鱼",
        "m.tb.cn" to "闲鱼",
        "weibo.com" to "微博",
        "weibo.cn" to "微博",
        "youtube.com" to "YouTube",
        "youtu.be" to "YouTube",
        "ixigua.com" to "西瓜视频",
        "acfun.cn" to "AcFun",
        "twitter.com" to "X",
        "x.com" to "X",
        "fxtwitter.com" to "X",
        "vxtwitter.com" to "X",
        "instagram.com" to "Instagram",
        "instagr.am" to "Instagram",
        "facebook.com" to "Facebook",
        "fb.watch" to "Facebook",
        "reddit.com" to "Reddit",
        "redd.it" to "Reddit",
        "vimeo.com" to "Vimeo",
    )

    fun extractUrl(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        val match = URL_RE.find(rawText.trim()) ?: return null
        var candidate = match.value.trimEnd { it in TRAILING_PUNCT }
        while (candidate.endsWith(")") && candidate.count { it == ')' } > candidate.count { it == '(' }) {
            candidate = candidate.dropLast(1)
        }
        return candidate.ifBlank { null }
    }

    fun hostnameOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            val host = URI(url).host.orEmpty().lowercase()
            host.trimEnd('.')
        } catch (_: Exception) {
            ""
        }
    }

    fun hostMatches(host: String, domain: String): Boolean {
        val cleanHost = host.lowercase().trimEnd('.')
        val cleanDomain = domain.lowercase().trimEnd('.')
        if (cleanHost.isEmpty() || cleanDomain.isEmpty()) return false
        return cleanHost == cleanDomain || cleanHost.endsWith(".$cleanDomain")
    }

    fun checkBlocked(url: String): String? {
        val host = hostnameOf(url)
        if (host.isEmpty()) return null
        for ((suffix, label) in BLOCKED_HOSTS) {
            if (hostMatches(host, suffix)) {
                return if (label == "微信视频号") {
                    "不支持微信视频号（需要登录态/抓包，本项目仅支持公开 UGC 视频）"
                } else {
                    "不支持 $label（会员/DRM 版权平台，本项目仅支持公开 UGC 视频）"
                }
            }
        }
        return null
    }

    fun platformOf(url: String): String {
        val host = hostnameOf(url)
        for ((suffix, label) in PLATFORM_HINTS) {
            if (hostMatches(host, suffix)) return label
        }
        return host.ifEmpty { "网页视频" }
    }

    fun isDouyin(url: String): Boolean {
        val host = hostnameOf(url)
        return hostMatches(host, "douyin.com") || hostMatches(host, "iesdouyin.com")
    }

    fun isKuaishou(url: String): Boolean {
        val host = hostnameOf(url)
        return hostMatches(host, "kuaishou.com") || hostMatches(host, "chenzhongtech.com")
    }

    fun isXianyu(url: String): Boolean {
        val host = hostnameOf(url)
        return hostMatches(host, "goofish.com") ||
            hostMatches(host, "2.taobao.com") ||
            (hostMatches(host, "market.m.taobao.com") && url.contains("idleFish", ignoreCase = true))
    }

    fun isXiaohongshu(url: String): Boolean {
        val host = hostnameOf(url)
        return hostMatches(host, "xiaohongshu.com") || hostMatches(host, "xhslink.com")
    }

    fun isBilibili(url: String): Boolean {
        val host = hostnameOf(url)
        return hostMatches(host, "bilibili.com") || hostMatches(host, "b23.tv")
    }

    fun isYouTube(url: String): Boolean {
        val host = hostnameOf(url)
        return hostMatches(host, "youtube.com") || hostMatches(host, "youtu.be")
    }

    fun isTikTok(url: String): Boolean {
        val host = hostnameOf(url)
        return hostMatches(host, "tiktok.com")
    }

    fun isXTwitter(url: String): Boolean {
        val host = hostnameOf(url)
        return hostMatches(host, "x.com") ||
            hostMatches(host, "twitter.com") ||
            hostMatches(host, "fxtwitter.com") ||
            hostMatches(host, "vxtwitter.com")
    }

    fun isInstagram(url: String): Boolean {
        val host = hostnameOf(url)
        return hostMatches(host, "instagram.com") || hostMatches(host, "instagr.am")
    }

    fun isFacebook(url: String): Boolean {
        val host = hostnameOf(url)
        return hostMatches(host, "facebook.com") || hostMatches(host, "fb.watch")
    }

    fun isReddit(url: String): Boolean {
        val host = hostnameOf(url)
        return hostMatches(host, "reddit.com") || hostMatches(host, "redd.it")
    }

    fun isVimeo(url: String): Boolean {
        val host = hostnameOf(url)
        return hostMatches(host, "vimeo.com")
    }

    fun awemeIdOf(url: String): String? {
        if (!isDouyin(url)) return null
        DOUYIN_ID_RE.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return queryParam(url, "modal_id")?.takeIf { it.all(Char::isDigit) && it.length in 10..25 }
    }

    fun kuaishouIdOf(url: String): String? {
        if (!isKuaishou(url)) return null
        KUAISHOU_ID_RE.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return queryParam(url, "photoId")
    }

    fun xianyuItemIdOf(url: String): String? {
        if (!isXianyu(url)) return null
        queryParam(url, "id")?.takeIf { it.all(Char::isDigit) && it.length in 6..20 }?.let { return it }
        queryParam(url, "itemId")?.takeIf { it.all(Char::isDigit) && it.length in 6..20 }?.let { return it }
        return XIANYU_ID_RE.find(url)?.groupValues?.getOrNull(1)
    }

    fun xhsNoteIdOf(url: String): String? {
        if (!isXiaohongshu(url)) return null
        return XHS_ID_RE.find(url)?.groupValues?.getOrNull(1)
    }

    fun bilibiliBvidOf(url: String): String? {
        if (!isBilibili(url)) return null
        return BV_ID_RE.find(url)?.groupValues?.getOrNull(1)
    }

    fun bilibiliAidOf(url: String): String? {
        if (!isBilibili(url)) return null
        return AV_ID_RE.find(url)?.groupValues?.getOrNull(1)
    }

    fun youtubeVideoIdOf(url: String): String? {
        if (!isYouTube(url)) return null
        queryParam(url, "v")?.takeIf { it.length == 11 }?.let { return it }
        return YT_ID_RE.find(url)?.groupValues?.getOrNull(1)
    }

    fun tweetIdOf(url: String): String? {
        if (!isXTwitter(url)) return null
        return TWEET_ID_RE.find(url)?.groupValues?.getOrNull(1)
    }

    fun instagramShortcodeOf(url: String): String? {
        if (!isInstagram(url)) return null
        return IG_CODE_RE.find(url)?.groupValues?.getOrNull(1)
    }

    fun vimeoIdOf(url: String): String? {
        if (!isVimeo(url)) return null
        return VIMEO_ID_RE.find(url)?.groupValues?.getOrNull(1)
    }

    fun extractTbJsRedirect(html: String): String? {
        val m = TB_JS_URL_RE.find(html) ?: return null
        val target = m.groupValues[1].trim()
        return if (target.startsWith("http://") || target.startsWith("https://")) target else null
    }

    fun normalizeCanonicalUrl(url: String): String {
        xianyuItemIdOf(url)?.let { return "https://h5.m.goofish.com/item?id=$it" }
        awemeIdOf(url)?.let { return "https://www.douyin.com/video/$it" }
        return url
    }

    fun expandShareUrl(url: String, client: OkHttpClient = HttpClients.default): String {
        var current = url
        val host = hostnameOf(current)
        if (hostMatches(host, "m.tb.cn")) {
            followTbShort(current, client)?.let { current = it }
        } else if (SHORT_LINK_HOSTS.any { hostMatches(host, it) }) {
            followRedirect(current, client)?.let { current = it }
        }
        return normalizeCanonicalUrl(current)
    }

    private fun followRedirect(url: String, client: OkHttpClient): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClients.MOBILE_UA)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                resp.request.url.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun followTbShort(url: String, client: OkHttpClient): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClients.MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                if (!hostMatches(hostnameOf(finalUrl), "m.tb.cn")) {
                    return finalUrl
                }
                val body = resp.body?.string()?.take(32_768).orEmpty()
                extractTbJsRedirect(body) ?: finalUrl
            }
        } catch (_: Exception) {
            null
        }
    }

    fun queryParam(url: String, key: String): String? {
        return try {
            val rawQuery = URI(url).rawQuery ?: return null
            for (pair in rawQuery.split('&')) {
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                val k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name())
                if (k == key) {
                    return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun sanitizeFilename(name: String, maxLen: Int = 80): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), "_")
            .trim()
            .trim('.')
            .take(maxLen)
        return cleaned.ifBlank { "video_${System.currentTimeMillis()}" }
    }
}
