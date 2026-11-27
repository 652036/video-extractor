package com.videoextractor.app.ui

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.download.DownloadBus
import com.videoextractor.app.download.DownloadService
import com.videoextractor.app.download.MediaDownloader
import com.videoextractor.app.engine.SniffHelper
import com.videoextractor.app.engine.VideoExtractorEngine
import com.videoextractor.app.model.DownloadState
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExtractorUiState(
    val inputText: String = "",
    val isParsing: Boolean = false,
    val stageMessage: String = "",
    val elapsedSeconds: Double? = null,
    val result: ExtractResult? = null,
    val downloadState: DownloadState = DownloadState.Idle,
    val previewEnabled: Boolean = false,
    val interactiveBrowserUrl: String? = null,
    val snackbarMessage: String? = null,
)

class ExtractorViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = VideoExtractorEngine(application.applicationContext)

    private val _state = MutableStateFlow(ExtractorUiState())
    val state: StateFlow<ExtractorUiState> = _state.asStateFlow()

    private var parseJob: Job? = null

    init {
        // Sync live background download state from DownloadService so progress survives
        // app switching, screen lock, and Activity recreation.
        viewModelScope.launch {
            DownloadBus.state.collect { dlState ->
                _state.update { cur ->
                    val restoredResult = if (cur.result == null && dlState !is DownloadState.Idle) {
                        DownloadBus.activeVideo.value?.let { ExtractResult.Success(it) }
                    } else {
                        cur.result
                    }
                    cur.copy(
                        result = restoredResult,
                        downloadState = dlState,
                    )
                }
            }
        }
    }

    fun onInputChanged(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun enablePreview() {
        _state.update { it.copy(previewEnabled = true) }
    }

    fun clearInput() {
        parseJob?.cancel()
        val wasDownloading = _state.value.downloadState is DownloadState.Downloading
        if (wasDownloading) {
            DownloadService.cancel(getApplication<Application>().applicationContext)
        } else {
            DownloadBus.resetIfNotDownloading()
            MediaDownloader.cleanupStaleTempFiles(getApplication<Application>().applicationContext)
        }
        _state.update {
            it.copy(
                inputText = "",
                isParsing = false,
                stageMessage = "",
                elapsedSeconds = null,
                result = null,
                previewEnabled = false,
                downloadState = DownloadState.Idle,
                snackbarMessage = if (wasDownloading) "已取消未完成的下载并清理临时文件" else it.snackbarMessage,
            )
        }
    }

    fun onSharedTextReceived(sharedText: String) {
        if (sharedText.isBlank()) return
        _state.update { it.copy(inputText = sharedText.trim()) }
        startExtract()
    }

    fun startExtract() {
        val raw = _state.value.inputText.trim()
        if (raw.isEmpty()) {
            _state.update {
                it.copy(result = ExtractResult.Failure("请先粘贴分享口令或视频链接"))
            }
            return
        }
        parseJob?.cancel()
        if (_state.value.downloadState !is DownloadState.Downloading) {
            DownloadBus.resetIfNotDownloading()
        }
        val startMs = System.currentTimeMillis()
        _state.update {
            it.copy(
                isParsing = true,
                stageMessage = "正在识别链接…",
                elapsedSeconds = null,
                result = null,
                previewEnabled = false,
            )
        }

        parseJob = viewModelScope.launch {
            val outcome = engine.extract(raw) { stage ->
                _state.update { it.copy(stageMessage = stage) }
            }
            val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
            _state.update {
                it.copy(
                    isParsing = false,
                    stageMessage = "",
                    elapsedSeconds = elapsed,
                    result = outcome,
                    previewEnabled = false,
                )
            }
        }
    }

    fun downloadCurrent() {
        val video = (_state.value.result as? ExtractResult.Success)?.video ?: return
        // Release ExoPlayer preview before downloading so it doesn't compete for bandwidth or CDN slots.
        _state.update { it.copy(previewEnabled = false) }
        DownloadService.start(getApplication<Application>().applicationContext, video)
    }

    fun cancelDownload() {
        DownloadService.cancel(getApplication<Application>().applicationContext)
        _state.update {
            it.copy(
                downloadState = DownloadState.Idle,
                snackbarMessage = "已取消下载并清理临时文件",
            )
        }
    }

    fun openInteractiveBrowser(targetUrl: String? = null) {
        val url = targetUrl ?: run {
            val extracted = UrlUtils.extractUrl(_state.value.inputText)
            if (extracted != null) SniffHelper.buildSniffTargetUrl(UrlUtils.normalizeCanonicalUrl(extracted))
            else null
        }
        if (url.isNullOrBlank()) {
            showToast("请先输入有效链接")
            return
        }
        UrlUtils.checkBlocked(url)?.let { blocked ->
            _state.update { it.copy(result = ExtractResult.Blocked(blocked)) }
            return
        }
        _state.update { it.copy(interactiveBrowserUrl = url) }
    }

    fun closeInteractiveBrowser(reExtractAfterClose: Boolean = false) {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
            // ignore
        }
        _state.update { it.copy(interactiveBrowserUrl = null) }
        if (reExtractAfterClose && _state.value.inputText.isNotBlank()) {
            startExtract()
        }
    }

    fun onInteractiveVideoCaptured(video: ExtractedVideo) {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
            // ignore
        }
        if (_state.value.downloadState !is DownloadState.Downloading) {
            DownloadBus.resetIfNotDownloading()
        }
        _state.update {
            it.copy(
                interactiveBrowserUrl = null,
                isParsing = false,
                stageMessage = "",
                result = ExtractResult.Success(video),
                previewEnabled = false,
            )
        }
    }

    fun clearLocalCookies() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            showToast("已清除本地 WebView 登录 Cookie")
        } catch (_: Throwable) {
            showToast("清除 Cookie 失败")
        }
    }

    fun showToast(msg: String) {
        _state.update { it.copy(snackbarMessage = msg) }
    }

    fun clearSnackbar() {
        _state.update { it.copy(snackbarMessage = null) }
    }
}
