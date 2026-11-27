package com.videoextractor.app.engine

import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * YouTube direct extractor using public InnerTube player API (`ANDROID_VR` / `IOS` / `ANDROID`).
 * Selects highest-quality muxed progressive MP4 (`streamingData.formats[]`) or HLS manifest so
 * Android plays and saves directly without FFmpeg.
 */
object YouTubeExtractor {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private data class ClientProfile(
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val clientIdHeader: String,
        val extraFields: Map<String, Any> = emptyMap(),
    )

    private val PROFILES = listOf(
        ClientProfile(
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            clientIdHeader = "28",
            extraFields = mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "osName" to "Android", "osVersion" to "12L"),
        ),
        ClientProfile(
            clientName = "IOS",
            clientVersion = "19.45.4",
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            clientIdHeader = "5",
            extraFields = mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iOS", "osVersion" to "18.1.0.22B83"),
        ),
        ClientProfile(
            clientName = "ANDROID",
            clientVersion = "19.44.38",
            userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip",
            clientIdHeader = "3",
            extraFields = mapOf("androidSdkVersion" to 34, "osName" to "Android", "osVersion" to "14"),
        ),
    )

    fun parsePlayerResponse(payload: JSONObject, pageUrl: String, userAgent: String = HttpClients.MOBILE_UA): ExtractResult? {
        val playability = payload.optJSONObject("playabilityStatus")
        val status = playability?.optString("status").orEmpty().uppercase()
        if (status == "LOGIN_REQUIRED") {
            val reason = playability?.optString("reason").orEmpty().ifBlank { "YouTube 需要登录或人机验证" }
            return ExtractResult.NeedsAuth(
                platform = "YouTube",
                pageUrl = pageUrl,
                message = "$reason — 请点击「内置浏览器打开」在手机本地登录 YouTube 后重试",
            )
        }
        if (status == "ERROR" || status == "UNPLAYABLE") {
            return null
        }

        val details = payload.optJSONObject("videoDetails")
        val title = details?.optString("title")?.trim()?.ifBlank { null }?.take(120) ?: "YouTube 视频"
        val thumbs = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val cover = if (thumbs != null && thumbs.length() > 0) {
            DouyinExtractor.normalizeUrl(thumbs.optJSONObject(thumbs.length() - 1)?.optString("url"))
        } else {
            null
        }

        val streaming = payload.optJSONObject("streamingData") ?: return null
        val formats = streaming.optJSONArray("formats")

        var bestUrl: String? = null
        var bestHeight = -1
        var bestSize: Long? = null
        var bestIsMp4 = false

        if (formats != null) {
            for (i in 0 until formats.length()) {
                val fmt = formats.optJSONObject(i) ?: continue
                val url = DouyinExtractor.normalizeUrl(fmt.optString("url")) ?: continue
                val mime = fmt.optString("mimeType").lowercase()
                val isMp4 = mime.startsWith("video/mp4")
                val height = fmt.optInt("height", 0)
                val size = fmt.optString("contentLength").toLongOrNull()

                val better = when {
                    bestUrl == null -> true
                    isMp4 && !bestIsMp4 -> true
                    isMp4 == bestIsMp4 && height > bestHeight -> true
                    else -> false
                }
                if (better) {
                    bestUrl = url
                    bestHeight = height
                    bestSize = size
                    bestIsMp4 = isMp4
                }
            }
        }

        if (bestUrl == null) {
            val hls = DouyinExtractor.normalizeUrl(streaming.optString("hlsManifestUrl"))
            if (hls != null) {
                return ExtractResult.Success(
                    ExtractedVideo(
                        title = title,
                        directUrl = hls,
                        pageUrl = pageUrl,
                        platform = "YouTube",
                        engine = "youtube-innertube",
                        ext = "m3u8",
                        thumbnailUrl = cover,
                        headers = mapOf("User-Agent" to userAgent),
                    )
                )
            }
            return null
        }

        return ExtractResult.Success(
            ExtractedVideo(
                title = title,
                directUrl = bestUrl,
                pageUrl = pageUrl,
                platform = "YouTube",
                engine = "youtube-innertube",
                ext = if (bestIsMp4) "mp4" else "webm",
                thumbnailUrl = cover,
                filesizeBytes = bestSize,
                headers = mapOf("User-Agent" to userAgent),
            )
        )
    }

    fun extract(url: String, client: OkHttpClient = HttpClients.default): ExtractResult? {
        val videoId = UrlUtils.youtubeVideoIdOf(url) ?: return null
        val pageUrl = "https://www.youtube.com/watch?v=$videoId"
        val cookies = HttpClients.webViewCookiesFor("https://www.youtube.com/")
        var lastAuth: ExtractResult.NeedsAuth? = null

        for (profile in PROFILES) {
            val clientObj = JSONObject()
                .put("clientName", profile.clientName)
                .put("clientVersion", profile.clientVersion)
                .put("hl", "zh-CN")
                .put("gl", "US")
            for ((k, v) in profile.extraFields) {
                clientObj.put(k, v)
            }
            val bodyJson = JSONObject()
                .put("videoId", videoId)
                .put("context", JSONObject().put("client", clientObj))
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)

            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", profile.userAgent)
                .header("X-YouTube-Client-Name", profile.clientIdHeader)
                .header("X-YouTube-Client-Version", profile.clientVersion)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", pageUrl)
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            if (cookies != null) reqBuilder.header("Cookie", cookies)

            val payload = try {
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else JSONObject(resp.body?.string().orEmpty())
                }
            } catch (_: Exception) {
                null
            } ?: continue

            when (val res = parsePlayerResponse(payload, pageUrl, profile.userAgent)) {
                is ExtractResult.Success -> return res
                is ExtractResult.NeedsAuth -> lastAuth = res
                else -> continue
            }
        }
        return lastAuth
    }
}
