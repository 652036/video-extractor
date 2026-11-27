package com.videoextractor.app.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.DownloadState
import com.videoextractor.app.model.ExtractedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object MediaDownloader {
    suspend fun downloadToGallery(
        context: Context,
        video: ExtractedVideo,
        onProgress: (DownloadState.Downloading) -> Unit,
    ): DownloadState = withContext(Dispatchers.IO) {
        if (video.isHls) {
            return@withContext DownloadState.Failed("当前直链为 HLS (.m3u8) 流媒体，暂不支持直接保存单文件，请点击「复制直链」使用播放器打开")
        }

        val ext = video.ext.lowercase().ifBlank { "mp4" }
        val baseName = UrlUtils.sanitizeFilename(video.title)
        val displayName = "${baseName}_${System.currentTimeMillis() % 10000}.$ext"
        val mimeType = if (ext == "webm") "video/webm" else "video/mp4"

        val reqBuilder = Request.Builder().url(video.directUrl)
        var hasUa = false
        for ((k, v) in video.headers) {
            if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
            reqBuilder.header(k, v)
        }
        if (!hasUa) {
            reqBuilder.header("User-Agent", HttpClients.MOBILE_UA)
        }
        HttpClients.webViewCookiesFor(video.directUrl)?.let { reqBuilder.header("Cookie", it) }

        try {
            HttpClients.download.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext DownloadState.Failed("下载失败（HTTP ${resp.code}）")
                }
                val body = resp.body ?: return@withContext DownloadState.Failed("响应体为空")
                val total = body.contentLength()

                val (outStream, savedUri, cleanupOnError) = openOutputStream(context, displayName, mimeType)
                    ?: return@withContext DownloadState.Failed("无法创建系统相册视频文件")

                try {
                    outStream.use { os ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(16 * 1024)
                            var readTotal = 0L
                            var lastEmit = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                os.write(buf, 0, n)
                                readTotal += n
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 120L || (total > 0 && readTotal >= total)) {
                                    lastEmit = now
                                    val progress = if (total > 0) (readTotal.toFloat() / total.toFloat()).coerceIn(0f, 1f) else -1f
                                    onProgress(DownloadState.Downloading(progress, readTotal, total))
                                }
                            }
                            os.flush()
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val doneValues = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }
                        context.contentResolver.update(savedUri, doneValues, null, null)
                    }
                    DownloadState.Saved(displayName = displayName, uriString = savedUri.toString())
                } catch (e: Exception) {
                    cleanupOnError()
                    DownloadState.Failed("写入文件失败：${e.message ?: "未知错误"}")
                }
            }
        } catch (e: Exception) {
            DownloadState.Failed("网络下载失败：${e.message ?: "未知错误"}")
        }
    }

    private data class OutputTarget(
        val stream: OutputStream,
        val uri: Uri,
        val cleanup: () -> Unit,
    )

    private fun openOutputStream(context: Context, displayName: String, mimeType: String): OutputTarget? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoExtractor")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val stream = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            OutputTarget(stream, uri) { resolver.delete(uri, null, null) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "VideoExtractor",
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, displayName)
            OutputTarget(FileOutputStream(file), Uri.fromFile(file)) { file.delete() }
        }
    }
}
