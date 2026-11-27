"""Guest share-page parsers for Douyin and Kuaishou. No login cookies required."""

from __future__ import annotations

import json
import re
import time
from typing import Any
from urllib.parse import unquote

import httpx

from ..config import MOBILE_UA, SHARE_TIMEOUT, UA
from ..cookies import current, httpx_cookie_dict
from ..urls import aweme_id_of, expand_share_url, is_douyin, is_kuaishou, photo_id_of

ROUTER_RE = re.compile(r"window\._ROUTER_DATA\s*=\s*(.*?)</script>", re.S | re.I)
RENDER_RE = re.compile(
    r'(?:<script[^>]+id=["\']RENDER_DATA["\'][^>]*>|window\.RENDER_DATA\s*=\s*)(.*?)(?:</script>|;?\s*</script>)',
    re.S | re.I,
)
SSR_RE = re.compile(r"window\._SSR_(?:HYDRATED_)?DATA\s*=\s*(.*?)</script>", re.S | re.I)
APOLLO_RE = re.compile(r"window\.__APOLLO_STATE__\s*=\s*(\{.*?\})\s*;", re.S)
INITIAL_RE = re.compile(r"window\.__INITIAL_STATE__\s*=\s*(\{.*?\})\s*;", re.S)
INIT_RE = re.compile(r"window\.INIT_STATE\s*=\s*(\{.*?\})\s*;?\s*</script>", re.S)
VIDEO_SRC_RE = re.compile(r"<video[^>]+src=[\"']([^\"']+)[\"']", re.I)
MEDIA_URL_RE = re.compile(
    r"https?://[^\s\"'<>\\]+?\.(?:mp4|m3u8)(?:\?[^\s\"'<>\\]*)?",
    re.I,
)

IPHONE_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 "
    "Mobile/15E148 Safari/604.1"
)
DOUYIN_APP_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 "
    "aweme/32.7.0 NetType/WIFI Channel/App Store"
)
ANDROID_UA = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
)

DOUYIN_HEADER_SETS = (
    {
        "User-Agent": IPHONE_UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer": "https://www.douyin.com/",
    },
    {
        "User-Agent": DOUYIN_APP_UA,
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Referer": "https://www.iesdouyin.com/",
    },
    {
        "User-Agent": ANDROID_UA,
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Referer": "https://www.douyin.com/",
    },
)

KUAISHOU_HEADER_SETS = (
    {
        "User-Agent": MOBILE_UA,
    },
    {
        "User-Agent": IPHONE_UA,
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Referer": "https://www.kuaishou.com/",
    },
    {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Referer": "https://v.kuaishou.com/",
    },
)


def _loads(raw: str) -> Any:
    raw = (raw or "").strip().rstrip("; \n\r\t")
    if not raw:
        return None
    if "%" in raw[:80] or (raw.startswith("%") or "%7B" in raw[:12].upper()):
        raw = unquote(raw)
    raw = raw.strip().rstrip("; \n\r\t")
    if not raw.startswith("{") and "{" in raw:
        raw = raw[raw.find("{") :]
    if raw.endswith("</script>"):
        raw = raw[: -len("</script>")].rstrip()
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        try:
            import json_repair

            return json_repair.loads(raw)
        except Exception:
            return None


def _walk_find(obj: Any, pred, _depth: int = 0) -> Any:
    if _depth > 14:
        return None
    if pred(obj):
        return obj
    if isinstance(obj, dict):
        for v in obj.values():
            found = _walk_find(v, pred, _depth + 1)
            if found is not None:
                return found
    elif isinstance(obj, list):
        for v in obj[:40]:
            found = _walk_find(v, pred, _depth + 1)
            if found is not None:
                return found
    return None


def _first_http(value: Any) -> str | None:
    if isinstance(value, str) and value.startswith("http"):
        return value
    if isinstance(value, list):
        for item in value:
            got = _first_http(item)
            if got:
                return got
    if isinstance(value, dict):
        for key in ("url_list", "urlList", "url", "src", "uri"):
            if key in value:
                got = _first_http(value[key])
                if got:
                    return got
    return None


def _dewatermark(url: str) -> str:
    if not url:
        return url
    return url.replace("playwm", "play").replace("play_wm", "play")


def _ok(url: str, *, title: str, direct: str, extractor_key: str, platform: str, thumb=None) -> dict:
    ext = "m3u8" if ".m3u8" in direct.lower() else "mp4"
    return {
        "ok": True,
        "title": title or "video",
        "ext": ext,
        "filesize": None,
        "thumbnail": thumb if isinstance(thumb, str) else None,
        "extractor": "share-page",
        "extractor_key": extractor_key,
        "platform": platform,
        "direct_url": direct,
        "needs_merge": ext == "m3u8",
        "page_url": url,
    }


class _Resp:
    def __init__(self, url: str, text: str, status_code: int = 200):
        self.url = url
        self.text = text
        self.status_code = status_code


def _fetch(url: str, headers: dict, timeout: float = 8.0, client: httpx.Client | None = None):
    extras = {"Accept-Encoding": "gzip, deflate"}
    merged = {**extras, **headers}
    cookies = httpx_cookie_dict(current()) or None
    last_err = None
    try:
        if client is not None:
            resp = client.get(url, headers=merged, timeout=timeout)
            resp.raise_for_status()
            return resp
        with httpx.Client(follow_redirects=True, timeout=timeout, headers=merged, cookies=cookies) as c:
            resp = c.get(url)
            resp.raise_for_status()
            return resp
    except Exception as exc:
        last_err = exc
    try:
        from curl_cffi import requests as cf

        r = cf.get(
            url,
            impersonate="chrome131",
            timeout=timeout,
            allow_redirects=True,
            headers=merged,
            cookies=cookies,
        )
        if r.status_code >= 400:
            raise RuntimeError(f"HTTP {r.status_code}")
        return _Resp(str(r.url), r.text or "", r.status_code)
    except Exception as exc:
        raise last_err or exc


class SharePageEngine:
    name = "share-page"
    stage = 10

    def available(self) -> bool:
        return True

    def version(self) -> str:
        return "share-html"

    def matches(self, url: str) -> bool:
        return is_douyin(url) or is_kuaishou(url)

    def extract(self, url: str) -> dict:
        if is_douyin(url):
            return _extract_douyin(url)
        if is_kuaishou(url):
            return _extract_kuaishou(url)
        return {"ok": False, "error": "share-page 仅处理抖音/快手"}


def _extract_douyin(url: str) -> dict:
    errors: list[str] = []
    deadline = time.monotonic() + SHARE_TIMEOUT
    aid = aweme_id_of(url)
    pages: list[str] = []

    def add(u: str | None) -> None:
        if u and u not in pages:
            pages.append(u)

    cookies = httpx_cookie_dict(current()) or None
    with httpx.Client(follow_redirects=True, cookies=cookies) as client:
        if not aid:
            try:
                remain = max(2.0, min(6.0, deadline - time.monotonic()))
                resp = _fetch(url, DOUYIN_HEADER_SETS[0], timeout=remain, client=client)
                aid = aweme_id_of(str(resp.url)) or aweme_id_of(resp.text[:8000])
                add(str(resp.url))
                parsed = _parse_douyin_html(str(resp.url), resp.text)
                if parsed:
                    return parsed
            except Exception as exc:
                errors.append(f"短链展开失败: {str(exc)[:120]}")
        if aid:
            add(f"https://www.iesdouyin.com/share/video/{aid}/")
            add(f"https://m.douyin.com/share/video/{aid}")
            add(f"https://www.iesdouyin.com/share/note/{aid}")
            add(f"https://www.douyin.com/video/{aid}")
        else:
            add(url)

        for headers in DOUYIN_HEADER_SETS:
            for page in pages:
                remain = deadline - time.monotonic()
                if remain <= 0.5:
                    break
                if not is_douyin(page) and "iesdouyin.com" not in page:
                    continue
                try:
                    resp = _fetch(page, headers, timeout=min(5.0, remain), client=client)
                except Exception as exc:
                    errors.append(f"{page}: {str(exc)[:100]}")
                    continue
                parsed = _parse_douyin_html(str(resp.url), resp.text)
                if parsed:
                    return parsed
                errors.append(f"{page}: HTML 无 play_addr")
            if time.monotonic() >= deadline - 0.5:
                break
    err = "；".join(dict.fromkeys(errors))[:240] or "iesdouyin 分享页未嵌入视频 JSON"
    return {"ok": False, "error": err}


def _parse_douyin_html(page: str, text: str) -> dict | None:
    blobs: list[Any] = []
    for cre in (ROUTER_RE, RENDER_RE, SSR_RE):
        m = cre.search(text or "")
        if not m:
            continue
        data = _loads(m.group(1))
        if data:
            blobs.append(data)
    item = None
    for data in blobs:
        item = _walk_find(
            data,
            lambda o: isinstance(o, dict)
            and isinstance(o.get("video"), dict)
            and (
                "play_addr" in (o.get("video") or {})
                or "download_addr" in (o.get("video") or {})
                or "playAddr" in (o.get("video") or {})
            ),
        )
        if item:
            break
        lst = _walk_find(data, lambda o: isinstance(o, dict) and o.get("item_list"))
        if isinstance(lst, dict) and lst.get("item_list"):
            item = lst["item_list"][0]
            break
    if not isinstance(item, dict):
        # last: any play_addr object in the HTML JSON-ish text
        play = _walk_find(
            blobs[0] if blobs else None,
            lambda o: isinstance(o, dict) and (o.get("url_list") or o.get("urlList")) and (o.get("uri") or o.get("url_key")),
        )
        if isinstance(play, dict):
            item = {"video": {"play_addr": play}, "desc": "douyin"}
    if not isinstance(item, dict):
        return None
    video = item.get("video") or {}
    play = video.get("play_addr") or video.get("playAddr") or video.get("download_addr") or {}
    url_list = []
    if isinstance(play, dict):
        url_list = play.get("url_list") or play.get("urlList") or []
        uri = play.get("uri")
    else:
        uri = None
        if isinstance(play, str):
            url_list = [play]
    direct = None
    if url_list:
        direct = _dewatermark(_first_http(url_list) or "")
    if not direct and uri:
        if str(uri).startswith("http"):
            direct = _dewatermark(str(uri))
        else:
            direct = f"https://www.iesdouyin.com/aweme/v1/play/?video_id={uri}&ratio=1080p&line=0"
    if not direct:
        return None
    title = item.get("desc") or item.get("preview_title") or item.get("caption") or "douyin"
    cover = None
    cov = video.get("cover") or video.get("origin_cover") or {}
    if isinstance(cov, dict):
        cover = _first_http(cov.get("url_list") or cov)
    return _ok(page, title=str(title), direct=direct, extractor_key="DouyinShare", platform="抖音", thumb=cover)


def _extract_kuaishou(url: str) -> dict:
    errors: list[str] = []
    deadline = time.monotonic() + SHARE_TIMEOUT
    pid = photo_id_of(url)
    expanded = url
    if not pid:
        expanded = expand_share_url(url)
        pid = photo_id_of(expanded) or photo_id_of(url)

    pages: list[str] = []

    def add(u: str | None) -> None:
        if u and u not in pages:
            pages.append(u)

    cookies = httpx_cookie_dict(current()) or None
    with httpx.Client(follow_redirects=True, cookies=cookies) as client:
        if not pid and time.monotonic() < deadline - 1.0:
            try:
                resp = _fetch(url, KUAISHOU_HEADER_SETS[0], timeout=min(6.0, deadline - time.monotonic()), client=client)
                pid = photo_id_of(str(resp.url))
                parsed = _parse_kuaishou_html(str(resp.url), resp.text)
                if parsed:
                    return parsed
                add(str(resp.url))
            except Exception as exc:
                errors.append(f"短链展开失败: {str(exc)[:120]}")
        if pid:
            # Prefer mobile share endpoints first: they embed INIT_STATE with playable mainMvUrls MP4.
            add(f"https://v.m.chenzhongtech.com/fw/photo/{pid}")
            add(f"https://m.gifshow.com/fw/photo/{pid}")
            add(f"https://www.kuaishou.com/short-video/{pid}")
        add(expanded)
        add(url)

        for headers in KUAISHOU_HEADER_SETS:
            for page in pages:
                remain = deadline - time.monotonic()
                if remain <= 0.5:
                    break
                try:
                    resp = _fetch(page, headers, timeout=min(6.0, remain), client=client)
                except Exception as exc:
                    errors.append(f"{page}: {str(exc)[:120]}")
                    continue
                parsed = _parse_kuaishou_html(str(resp.url), resp.text)
                if parsed:
                    return parsed
                errors.append(f"{page}: 无 CDN 地址")
            if time.monotonic() >= deadline - 0.5:
                break
    err = "；".join(dict.fromkeys(errors))[:240] or "快手分享页未找到播放地址"
    return {"ok": False, "error": err}


def _url_from_adaptation_sets(sets: Any) -> str | None:
    if not isinstance(sets, list):
        return None
    best_url = None
    best_bitrate = -1
    for aset in sets:
        if not isinstance(aset, dict):
            continue
        for rep in aset.get("representation") or []:
            if not isinstance(rep, dict):
                continue
            u = rep.get("url")
            if not isinstance(u, str) or not u.startswith("http"):
                continue
            bitrate = rep.get("maxBitrate") or rep.get("avgBitrate") or 0
            try:
                bitrate = int(bitrate)
            except Exception:
                bitrate = 0
            if bitrate >= best_bitrate:
                best_url = u
                best_bitrate = bitrate
    return best_url


def _parse_kuaishou_html(page: str, text: str) -> dict | None:
    text = text or ""
    blobs: list[Any] = []
    for cre in (APOLLO_RE, INITIAL_RE, INIT_RE):
        m = cre.search(text)
        if not m:
            continue
        data = _loads(m.group(1))
        if data:
            blobs.append(data)

    photo = None
    for data in blobs:
        photo = _walk_find(
            data,
            lambda o: isinstance(o, dict)
            and (
                o.get("__typename") == "VisionVideoDetailPhoto"
                or o.get("mainMvUrls")
                or o.get("photoUrl")
                or o.get("photoH265Url")
            ),
        )
        if photo:
            break
    title = "kuaishou"
    cover = None
    direct = None
    if isinstance(photo, dict):
        title = photo.get("caption") or photo.get("title") or title
        cover = (
            photo.get("coverUrl")
            or photo.get("poster")
            or _first_http(photo.get("coverUrls") or photo.get("webpCoverUrls"))
        )
        direct = (
            _first_http(photo.get("mainMvUrls"))
            or photo.get("photoUrl")
            or photo.get("photoH265Url")
        )
        if not direct:
            manifest = photo.get("manifest") or {}
            if isinstance(manifest, dict):
                direct = _url_from_adaptation_sets(manifest.get("adaptationSet"))
        if not direct:
            vr = photo.get("videoResource") or {}
            j = (vr.get("json") if isinstance(vr, dict) else None) or {}
            for codec in ("h264", "hevc"):
                if isinstance(j, dict):
                    direct = _url_from_adaptation_sets((j.get(codec) or {}).get("adaptationSet"))
                if direct:
                    break

    if not direct:
        m = VIDEO_SRC_RE.search(text)
        if m and m.group(1).startswith("http"):
            direct = m.group(1)

    if not direct:
        candidates = _media_urls(text)
        if candidates:
            direct = candidates[0]

    if not direct:
        return None
    if not title or title == "kuaishou":
        tm = re.search(r"<title>([^<]+)</title>", text, re.I)
        if tm:
            title = tm.group(1).replace(" - 快手", "").strip() or title
    return _ok(page, title=str(title), direct=direct, extractor_key="KuaishouShare", platform="快手", thumb=cover)


def _media_urls(text: str) -> list[str]:
    blob = (text or "").replace("\\u002F", "/").replace("\\/", "/")
    found: list[str] = []
    for raw in MEDIA_URL_RE.findall(blob):
        url = raw.replace("&amp;", "&")
        if url not in found and "http" in url:
            found.append(url)
    # Prefer direct playable CDN MP4s over encrypted/remux HLS placeholders (which often 403).
    def _score(u: str) -> tuple[int, int, int]:
        low = u.lower()
        is_enc_remux = 1 if ("realtime_remux" in low or "/enc_" in low) else 0
        is_m3u8 = 1 if ".m3u8" in low else 0
        is_ks = 0 if any(h in low for h in ("kwimgs", "yximgs", "kwaicdn", "gifshow", "oskwai")) else 1
        return (is_enc_remux, is_ks, is_m3u8)

    return sorted(found, key=_score)
