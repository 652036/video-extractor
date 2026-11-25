"""In-page video preview: reverse-proxy the CDN so the browser can play it."""

from __future__ import annotations

import logging
from pathlib import Path

import httpx
from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse, Response, StreamingResponse

from .config import DOWNLOAD_TIMEOUT
from .download import _dl_headers
from .jobs import STORE
from .util import is_hls

log = logging.getLogger("unwatermark")

router = APIRouter()

_TIMEOUT = httpx.Timeout(connect=20.0, read=DOWNLOAD_TIMEOUT, write=30.0, pool=20.0)
_LIMITS = httpx.Limits(max_connections=40, max_keepalive_connections=16)
_UNSUPPORTED = {"ok": False, "error": "该格式暂不支持预览"}
_CLIENT: httpx.AsyncClient | None = None


def _get_client() -> httpx.AsyncClient:
    global _CLIENT
    if _CLIENT is None or _CLIENT.is_closed:
        _CLIENT = httpx.AsyncClient(follow_redirects=True, timeout=_TIMEOUT, limits=_LIMITS)
    return _CLIENT


async def close_client() -> None:
    global _CLIENT
    if _CLIENT is not None and not _CLIENT.is_closed:
        await _CLIENT.aclose()
    _CLIENT = None


def _media_type(upstream: str | None, ext: str | None) -> str:
    ct = (upstream or "").split(";")[0].strip().lower()
    if ct.startswith("video/") or ct in {"application/octet-stream", "application/mp4"}:
        return upstream.split(";")[0].strip() if upstream else "video/mp4"
    ext = (ext or "").lower().lstrip(".")
    if ext in {"webm"}:
        return "video/webm"
    if ext in {"mov", "qt"}:
        return "video/quicktime"
    if ext in {"m4v"}:
        return "video/x-m4v"
    return "video/mp4"


@router.api_route("/api/preview", methods=["GET", "HEAD"])
async def preview(request: Request, job: str = Query(..., min_length=8, max_length=64)):
    item = STORE.get(job)
    if item is None:
        raise HTTPException(404, "任务不存在或已过期，请重新解析")

    # Serve already-downloaded local file directly (supports Range/seeking even for merged DASH/HLS).
    if item.filepath and Path(item.filepath).is_file() and Path(item.filepath).stat().st_size > 0:
        media = _media_type(None, Path(item.filepath).suffix or item.ext)
        return FileResponse(item.filepath, media_type=media, headers={"Cache-Control": "no-store"})

    extra = item.extra or {}
    target_url = item.direct_url or extra.get("preview_direct_url")
    if not target_url or not str(target_url).startswith("http") or is_hls(target_url):
        return JSONResponse(_UNSUPPORTED, status_code=415)

    headers = _dl_headers(target_url, item.page_url, extra_headers=extra.get("http_headers"))
    rng = request.headers.get("range")
    if rng:
        headers["Range"] = rng
    elif request.method == "HEAD":
        # Avoid initiating a full upstream body transfer for HEAD metadata probes.
        headers["Range"] = "bytes=0-0"

    client = _get_client()
    try:
        req = client.build_request("GET", target_url, headers=headers)
        resp = await client.send(req, stream=True)
    except Exception as exc:
        log.warning("preview upstream open failed job=%s err=%s", job, exc)
        raise HTTPException(502, "预览失败") from exc

    if resp.status_code >= 400:
        await resp.aclose()
        log.warning("preview upstream status=%s job=%s", resp.status_code, job)
        raise HTTPException(502, "预览失败")

    status = 206 if resp.status_code == 206 else 200
    media = _media_type(resp.headers.get("content-type"), item.ext)
    out = {
        "Accept-Ranges": resp.headers.get("accept-ranges") or "bytes",
        "Content-Disposition": "inline",
        "Cache-Control": "no-store",
    }
    if resp.headers.get("content-range"):
        out["Content-Range"] = resp.headers["content-range"]
    if resp.headers.get("content-length"):
        out["Content-Length"] = resp.headers["content-length"]

    if request.method == "HEAD":
        # If we probed with Range: bytes=0-0, derive full Content-Length from Content-Range.
        if not rng and resp.headers.get("content-range") and "/" in resp.headers["content-range"]:
            total = resp.headers["content-range"].rsplit("/", 1)[-1].strip()
            if total.isdigit():
                out["Content-Length"] = total
            out.pop("Content-Range", None)
            status = 200
        await resp.aclose()
        return Response(status_code=status, media_type=media, headers=out)

    async def body():
        try:
            async for chunk in resp.aiter_bytes(64 * 1024):
                yield chunk
        finally:
            await resp.aclose()

    return StreamingResponse(body(), status_code=status, media_type=media, headers=out)
