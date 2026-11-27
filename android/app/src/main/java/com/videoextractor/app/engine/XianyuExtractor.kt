package com.videoextractor.app.engine

import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Xianyu (闲鱼 / goofish.com / m.tb.cn) direct MTOP extractor.
 * - Without local WebView cookies: prioritizes H5 guest API (`mtop.taobao.idle.awesome.detail`)
 *   which works anonymously on mobile / residential IPs.
 * - With local WebView cookies: prioritizes PC API (`mtop.taobao.idle.pc.detail`) and upgrades
 *   to UD/HD via `mtop.idle.cloud.video.query`.
 * - Respects auth/risk gates (`RGV587_ERROR` / Baxia): never solves CAPTCHAs; returns NeedsAuth
 *   so the user can sign in via the local in-app WebView if needed.
 */
object XianyuExtractor {
    const val MTOP_APP_KEY_PC = "34839810"
    const val MTOP_APP_KEY_H5 = "12574478"
    const val MTOP_DETAIL_PC = "mtop.taobao.idle.pc.detail"
    const val MTOP_DETAIL_H5 = "mtop.taobao.idle.awesome.detail"
    const val MTOP_VIDEO_QUERY = "mtop.idle.cloud.video.query"

    private val REJECT_SUBSTRS = listOf("punish", "passport.", "bixi.alicdn", "mini_login", "captcha")
    private val AUTH_RET_HINTS = listOf(
        "rgv587_error",
        "fail_sys_user_validate",
        "fail_sys_session_expired",
        "fail_sys_illegal_access",
        "session过期",
        "请登录",
    )
    private val TOKEN_RETRY_HINTS = listOf(
        "fail_sys_token_empty",
        "fail_sys_token_expired",
        "fail_sys_token_exoired",
        "令牌过期",
        "令牌为空",
    )

    data class ParsedDetail(
        val result: ExtractResult?,
        val mtsVid: String? = null,
    )

    fun mtopSign(token: String, ts: String, appKey: String, dataStr: String): String {
        val raw = "$token&$ts&$appKey&$dataStr"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun normHttp(value: Any?): String? {
        val str = (value as? String)?.trim() ?: return null
        val u = when {
            str.startsWith("//") -> "https:$str"
            str.startsWith("http://") -> "https://" + str.removePrefix("http://")
            str.startsWith("https://") -> str
            else -> return null
        }
        val low = u.lowercase()
        if (REJECT_SUBSTRS.any { low.contains(it) }) return null
        return u
    }

    private fun looksMedia(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val low = url.lowercase()
        if (REJECT_SUBSTRS.any { low.contains(it) }) return false
        return low.contains(".mp4") ||
            low.contains(".m3u8") ||
            low.contains("cloud.video.taobao.com") ||
            low.contains("tbvideo") ||
            low.contains("video.goofish")
    }

    private fun walkMediaUrl(obj: Any?, depth: Int = 0): String? {
        if (obj == null || depth > 12) return null
        when (obj) {
            is String -> {
                val u = normHttp(obj)
                if (u != null && looksMedia(u)) return u
            }
            is JSONObject -> {
                for (k in listOf("video_url", "playUrl", "videoUrl", "videoPlayUrl", "hdUrl", "highUrl", "sd480pUrl", "ld320pUrl", "url")) {
                    if (obj.has(k)) {
                        val u = normHttp(obj.optString(k))
                        if (u != null && looksMedia(u)) return u
                    }
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val found = walkMediaUrl(obj.opt(keys.next()), depth + 1)
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until minOf(obj.length(), 40)) {
                    val found = walkMediaUrl(obj.opt(i), depth + 1)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    fun isAuthOrRisk(payload: JSONObject): Boolean {
        val ret = payload.optJSONArray("ret")?.let { arr ->
            (0 until arr.length()).joinToString(" ") { arr.optString(it) }.lowercase()
        }.orEmpty()
        if (AUTH_RET_HINTS.any { ret.contains(it) }) return true
        val dataUrl = payload.optJSONObject("data")?.optString("url").orEmpty().lowercase()
        return dataUrl.contains("punish") || dataUrl.contains("passport.goofish") || dataUrl.contains("mini_login")
    }

    private fun needsTokenRetry(payload: JSONObject): Boolean {
        val ret = payload.optJSONArray("ret")?.let { arr ->
            (0 until arr.length()).joinToString(" ") { arr.optString(it) }.lowercase()
        }.orEmpty()
        return TOKEN_RETRY_HINTS.any { ret.contains(it) }
    }

    fun parseVideoQuery(payload: JSONObject): String? {
        val data = payload.optJSONObject("data") ?: return null
        val resources = data.optJSONObject("cdnVideo")?.optJSONArray("resources")
        if (resources != null) {
            for (i in 0 until resources.length()) {
                val res = resources.optJSONObject(i) ?: continue
                val u = normHttp(res.optString("video_url"))
                    ?: normHttp(res.optString("url"))
                    ?: normHttp(res.optString("playUrl"))
                if (u != null) return u
            }
        }
        return walkMediaUrl(data)
    }

    fun parseDetailPayload(payload: JSONObject, pageUrl: String): ParsedDetail {
        if (isAuthOrRisk(payload)) {
            return ParsedDetail(
                ExtractResult.NeedsAuth(
                    platform = "闲鱼",
                    pageUrl = pageUrl,
                    message = "闲鱼触发风控或需要登录态，可点下方「内置浏览器打开」在手机本地验证/登录后直接嗅探",
                )
            )
        }
        val data = payload.optJSONObject("data") ?: return ParsedDetail(null)
        val itemDo = data.optJSONObject("itemDO")
            ?: data.optJSONObject("itemDo")
            ?: data.optJSONObject("item")
            ?: return ParsedDetail(null)

        val rawTitle = itemDo.optString("title").ifBlank {
            itemDo.optString("desc").ifBlank {
                itemDo.optString("richTextDesc").ifBlank { "闲鱼视频" }
            }
        }
        val title = rawTitle.trim().lines().firstOrNull()?.take(120)?.ifBlank { "闲鱼视频" } ?: "闲鱼视频"

        val vpi = itemDo.optJSONObject("videoPlayInfo")
            ?: itemDo.optJSONObject("videoInfo")
            ?: itemDo.optJSONObject("itemVideoDO")
            ?: JSONObject()

        var direct = listOf(
            "playUrl", "videoUrl", "video_url", "play_url",
            "hdUrl", "highUrl", "sd480pUrl", "ld320pUrl",
        ).firstNotNullOfOrNull { normHttp(vpi.optString(it)) }

        var mtsVid = listOf(
            vpi.optString("mtsVid"),
            vpi.optString("videoId"),
            itemDo.optString("videoId"),
        ).firstOrNull { it.isNotBlank() }?.trim()

        var thumb = listOf("url", "coverUrl", "cover").firstNotNullOfOrNull { normHttp(vpi.optString(it)) }
        val images = itemDo.optJSONArray("imageInfos")
        if (images != null) {
            for (i in 0 until images.length()) {
                val img = images.optJSONObject(i) ?: continue
                if (thumb == null) thumb = normHttp(img.optString("url"))
                if (direct == null) {
                    direct = normHttp(img.optString("videoUrl")) ?: normHttp(img.optString("playUrl"))
                }
                if (mtsVid == null && img.optString("mtsVid").isNotBlank()) {
                    mtsVid = img.optString("mtsVid").trim()
                }
            }
        }

        if (direct == null) {
            direct = walkMediaUrl(itemDo)
        }

        if (direct == null && mtsVid == null) {
            val hasListing = itemDo.has("itemId") || itemDo.optString("title").isNotBlank() ||
                itemDo.optString("desc").isNotBlank() || (images != null && images.length() > 0)
            return if (hasListing) {
                ParsedDetail(ExtractResult.NoVideo("该闲鱼商品没有包含视频（仅图片）"))
            } else {
                ParsedDetail(null)
            }
        }

        if (direct == null) {
            return ParsedDetail(null, mtsVid = mtsVid)
        }

        return ParsedDetail(
            result = ExtractResult.Success(
                ExtractedVideo(
                    title = title,
                    directUrl = direct,
                    pageUrl = pageUrl,
                    platform = "闲鱼",
                    engine = "xianyu-mtop",
                    thumbnailUrl = thumb,
                    headers = mapOf(
                        "User-Agent" to HttpClients.MOBILE_UA,
                        "Referer" to "https://www.goofish.com/",
                    ),
                )
            ),
            mtsVid = mtsVid,
        )
    }

    private fun callMtop(
        client: OkHttpClient,
        api: String,
        appKey: String,
        dataJson: JSONObject,
        referer: String,
        origin: String,
        ua: String,
        cookieJar: MutableMap<String, String>,
    ): JSONObject? {
        val endpoint = "https://h5api.m.goofish.com/h5/$api/1.0/"
        val dataStr = dataJson.toString()

        for (attempt in 0..1) {
            val tk = cookieJar["_m_h5_tk"].orEmpty()
            val token = tk.substringBefore("_", "")
            val ts = System.currentTimeMillis().toString()
            val sign = if (token.isNotEmpty()) mtopSign(token, ts, appKey, dataStr) else "0"

            val url = endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("jsv", "2.7.2")
                .addQueryParameter("appKey", appKey)
                .addQueryParameter("t", ts)
                .addQueryParameter("sign", sign)
                .addQueryParameter("v", "1.0")
                .addQueryParameter("type", "originaljson")
                .addQueryParameter("dataType", "json")
                .addQueryParameter("api", api)
                .build()

            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Referer", referer)
                .header("Origin", origin)
                .header("Accept", "application/json")
                .post(FormBody.Builder().add("data", dataStr).build())

            if (cookieJar.isNotEmpty()) {
                val cookieStr = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
                reqBuilder.header("Cookie", cookieStr)
            }

            val payload = try {
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    for (setCookie in resp.headers("Set-Cookie")) {
                        val firstPart = setCookie.substringBefore(';')
                        val idx = firstPart.indexOf('=')
                        if (idx > 0) {
                            val k = firstPart.substring(0, idx).trim()
                            val v = firstPart.substring(idx + 1).trim()
                            if (k.isNotEmpty()) cookieJar[k] = v
                        }
                    }
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) null else JSONObject(body)
                }
            } catch (_: Exception) {
                null
            } ?: return null

            if (attempt == 0 && (needsTokenRetry(payload) || token.isEmpty())) {
                val newTk = cookieJar["_m_h5_tk"].orEmpty()
                if (newTk.isNotEmpty() && newTk != tk) {
                    continue
                }
            }
            return payload
        }
        return null
    }

    fun extract(url: String, client: OkHttpClient = HttpClients.default): ExtractResult? {
        val itemId = UrlUtils.xianyuItemIdOf(url) ?: return null
        val pageUrl = "https://h5.m.goofish.com/item?id=$itemId"

        val webCookies = HttpClients.parseCookieHeader(
            listOfNotNull(
                HttpClients.webViewCookiesFor("https://www.goofish.com/"),
                HttpClients.webViewCookiesFor("https://h5.m.goofish.com/"),
                HttpClients.webViewCookiesFor("https://h5api.m.goofish.com/"),
            ).joinToString("; ")
        )
        val hasLoginCookies = webCookies.containsKey("cookie2") || webCookies.containsKey("unb")

        data class Plan(val api: String, val appKey: String, val referer: String, val origin: String, val ua: String)

        val h5Plan = Plan(MTOP_DETAIL_H5, MTOP_APP_KEY_H5, "https://h5.m.goofish.com/", "https://h5.m.goofish.com", HttpClients.MOBILE_UA)
        val pcPlan = Plan(MTOP_DETAIL_PC, MTOP_APP_KEY_PC, "https://www.goofish.com/", "https://www.goofish.com", HttpClients.DESKTOP_UA)
        val plans = if (hasLoginCookies) listOf(pcPlan, h5Plan) else listOf(h5Plan, pcPlan)

        var lastAuth: ExtractResult.NeedsAuth? = null
        for (plan in plans) {
            val jar = webCookies.toMutableMap()
            val payload = callMtop(
                client = client,
                api = plan.api,
                appKey = plan.appKey,
                dataJson = JSONObject().put("itemId", itemId),
                referer = plan.referer,
                origin = plan.origin,
                ua = plan.ua,
                cookieJar = jar,
            ) ?: continue

            val parsed = parseDetailPayload(payload, pageUrl)
            when (val res = parsed.result) {
                is ExtractResult.NoVideo -> return res
                is ExtractResult.NeedsAuth -> {
                    lastAuth = res
                    continue
                }
                is ExtractResult.Success -> {
                    var video = res.video
                    if (!parsed.mtsVid.isNullOrBlank()) {
                        val vqPayload = callMtop(
                            client = client,
                            api = MTOP_VIDEO_QUERY,
                            appKey = MTOP_APP_KEY_PC,
                            dataJson = JSONObject()
                                .put("videoId", parsed.mtsVid)
                                .put("expectedDefPriority", "ud#hd#sd#ld")
                                .put("from", "xianyuPC")
                                .put("playScenes", "xianyu")
                                .put("expectedCodec", "h264"),
                            referer = "https://www.goofish.com/",
                            origin = "https://www.goofish.com",
                            ua = HttpClients.DESKTOP_UA,
                            cookieJar = jar,
                        )
                        val hdUrl = vqPayload?.let { parseVideoQuery(it) }
                        if (!hdUrl.isNullOrBlank()) {
                            video = video.copy(directUrl = hdUrl)
                        }
                    }
                    return ExtractResult.Success(video)
                }
                else -> continue
            }
        }
        return lastAuth
    }
}
