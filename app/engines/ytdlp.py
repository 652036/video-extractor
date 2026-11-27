"""yt-dlp engine (required). Prefer watermark-free formats for TikTok/Douyin."""

from __future__ import annotations

from urllib.parse import urlparse

import yt_dlp

from ..config import UA
from ..cookies import current, ytdlp_cookie_opts
from ..util import looks_like_login_error, platform_of


def _format_for(url: str) -> str:
    host = (urlparse(url).hostname or "").lower()
    if "tiktok.com" in host or "douyin.com" in host or "iesdouyin.com" in host:
        # 'download' is the classic no-watermark TikTok format id
        return "download/best[format_note!*=watermark]/bv*+ba/b"
    return "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/best"


def _ydl_opts(url: str, *, download: bool = False, outtmpl: str | None = None) -> dict:
    opts: dict = {
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "noplaylist": True,
        "skip_download": not download,
        "format": _format_for(url),
        "merge_output_format": "mp4",
        "socket_timeout": 15,
        "retries": 2,
        "http_headers": {"User-Agent": UA},
        "cachedir": False,
        "extractor_args": {"twitter": {"api": ["syndication", "legacy"]}},
    }
    cookie_opts = ytdlp_cookie_opts(current())
    opts.update(cookie_opts)
    # Logged-in / cookie path: Vimeo anonymous macos OAuth is dead; web client needs cookies.
    if cookie_opts:
        args = dict(opts.get("extractor_args") or {})
        args.setdefault("vimeo", {})
        args["vimeo"] = dict(args.get("vimeo") or {})
        args["vimeo"].setdefault("client", ["web"])
        opts["extractor_args"] = args
    if outtmpl:
        opts["outtmpl"] = outtmpl
        opts["restrictfilenames"] = False
    return opts


class YtDlpEngine:
    stage = 20
    name = "yt-dlp"

    def available(self) -> bool:
        return True

    def version(self) -> str:
        return getattr(yt_dlp.version, "__version__", "unknown")

    def extract(self, url: str) -> dict:
        try:
            with yt_dlp.YoutubeDL(_ydl_opts(url)) as ydl:
                info = ydl.extract_info(url, download=False)
        except yt_dlp.utils.DownloadError as exc:
            msg = str(exc)
            if looks_like_login_error(msg):
                return {"ok": False, "error": "needs cookie", "needs_cookie": True}
            return {"ok": False, "error": _short_err(msg)}
        except Exception as exc:
            msg = str(exc)
            if looks_like_login_error(msg):
                return {"ok": False, "error": "needs cookie", "needs_cookie": True}
            return {"ok": False, "error": _short_err(msg)}

        if not info:
            return {"ok": False, "error": "yt-dlp 未返回信息"}
        if info.get("_type") == "playlist" and info.get("entries"):
            info = info["entries"][0] or {}

        requested = info.get("requested_downloads") or []
        fmt = requested[0] if requested else info
        req_formats = fmt.get("requested_formats") or info.get("requested_formats") or []
        direct = fmt.get("url") or info.get("url")
        ext = (fmt.get("ext") or info.get("ext") or "mp4").lstrip(".")
        filesize = fmt.get("filesize") or fmt.get("filesize_approx") or info.get("filesize") or info.get("filesize_approx")
        if not filesize and req_formats:
            sizes = [f.get("filesize") or f.get("filesize_approx") for f in req_formats if isinstance(f, dict)]
            if sizes and all(sizes):
                filesize = sum(int(s) for s in sizes)
        vcodec = fmt.get("vcodec")
        acodec = fmt.get("acodec")
        needs_merge = bool(
            (not direct)
            or len(req_formats) > 1
            or (vcodec and vcodec != "none" and acodec == "none")
            or (acodec and acodec != "none" and vcodec == "none")
        )
        http_headers = _extract_headers(fmt, info, req_formats)
        preview_direct = direct
        preview_headers = http_headers
        if needs_merge or not preview_direct:
            p_url, p_headers = _pick_preview_format(info.get("formats") or [])
            if p_url:
                preview_direct = p_url
                if p_headers:
                    preview_headers = p_headers
        ie = info.get("extractor_key") or info.get("ie_key") or info.get("extractor") or "yt-dlp"
        title = info.get("title") or info.get("fulltitle") or "video"
        thumb = info.get("thumbnail")
        if not thumb and info.get("thumbnails"):
            thumb = (info["thumbnails"][-1] or {}).get("url")
        if not direct and not info.get("formats"):
            return {"ok": False, "error": "未找到可下载的媒体"}
        return {
            "ok": True,
            "title": title,
            "ext": ext or "mp4",
            "filesize": int(filesize) if filesize else None,
            "thumbnail": thumb,
            "extractor": self.name,
            "extractor_key": ie,
            "platform": platform_of(url, ie),
            "direct_url": direct,
            "preview_direct_url": preview_direct,
            "http_headers": preview_headers,
            "needs_merge": needs_merge,
            "page_url": url,
        }

    def download_to(self, url: str, outtmpl: str) -> str | None:
        opts = _ydl_opts(url, download=True, outtmpl=outtmpl)
        opts["skip_download"] = False
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            if not info:
                return None
            if info.get("_type") == "playlist" and info.get("entries"):
                info = info["entries"][0] or {}
            req = info.get("requested_downloads") or []
            path = (req[0].get("filepath") if req and isinstance(req[0], dict) else None) or info.get("_filename")
            if path:
                return path
            prepared = ydl.prepare_filename(info)
            return prepared


def _extract_headers(fmt: dict, info: dict, req_formats: list) -> dict[str, str]:
    raw = fmt.get("http_headers") or info.get("http_headers")
    if not raw and req_formats and isinstance(req_formats[0], dict):
        raw = req_formats[0].get("http_headers")
    if not isinstance(raw, dict):
        return {}
    return {str(k): str(v) for k, v in raw.items() if k and v is not None}


def _pick_preview_format(formats: list) -> tuple[str | None, dict[str, str]]:
    """Find best progressive (video+audio, non-HLS) stream for in-browser preview."""
    best = None
    best_score = (-1, -1)
    for f in formats:
        if not isinstance(f, dict):
            continue
        u = f.get("url")
        if not isinstance(u, str) or not u.startswith("http"):
            continue
        if ".m3u8" in u.lower() or f.get("protocol") in {"m3u8", "m3u8_native", "http_dash_segments"}:
            continue
        vc = f.get("vcodec")
        ac = f.get("acodec")
        if not vc or vc == "none" or not ac or ac == "none":
            continue
        ext = (f.get("ext") or "").lower()
        is_mp4 = 1 if ext in {"mp4", "m4v"} else (0 if ext == "webm" else -1)
        if is_mp4 < 0:
            continue
        height = int(f.get("height") or 0)
        score = (is_mp4, height)
        if score > best_score:
            best_score = score
            best = f
    if not best:
        return None, {}
    raw_h = best.get("http_headers")
    hdrs = {str(k): str(v) for k, v in raw_h.items() if k and v is not None} if isinstance(raw_h, dict) else {}
    return best.get("url"), hdrs


def _short_err(msg: str) -> str:
    line = msg.strip().splitlines()[-1] if msg.strip() else "yt-dlp 解析失败"
    if "ERROR:" in line:
        line = line.split("ERROR:", 1)[-1].strip()
    return line[:240]
