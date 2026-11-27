"""Xianyu (闲鱼 / goofish.com) video extractor (stage 15).

Extracts public listing videos from Xianyu share links (`m.tb.cn` -> `goofish.com/item?id=...`):
  1. Direct MTOP API (`mtop.taobao.idle.pc.detail` v1.0 + `mtop.idle.cloud.video.query` v1.0 for HD).
  2. Playwright headless Chrome passive capture fallback (listens to MTOP JSON & CDN media URLs).

Respects platform auth gates: never solves/bypasses CAPTCHAs or steals logins.
When Xianyu returns `RGV587_ERROR` / login wall, returns `needs_cookie=True` so users
can supply their own local `cookies.txt` or `--cookies-from-browser`.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
import threading
import time
from typing import Any
from urllib.parse import urlparse

import httpx

from ..config import BROWSER_TIMEOUT, UA
from ..cookies import cookies_for_domains, current
from ..urls import expand_share_url, is_xianyu, xianyu_item_id_of
from ..util import platform_of

log = logging.getLogger("unwatermark")

NAME = "xianyu"
STAGE = 15
MTOP_APP_KEY_PC = "34839810"
MTOP_APP_KEY_H5 = "12574478"
MTOP_DETAIL_PC = "mtop.taobao.idle.pc.detail"
MTOP_DETAIL_H5 = "mtop.taobao.idle.awesome.detail"
MTOP_VIDEO_QUERY = "mtop.idle.cloud.video.query"
XIANYU_COOKIE_DOMAINS = ("goofish.com", "taobao.com")

_MEDIA_URL_RE = re.compile(
    r"https?://[^\s\"'<>\\]+?(?:\.(?:mp4|m3u8)(?:\?[^\s\"'<>\\]*)?|cloud\.video\.taobao\.com/[^\s\"'<>\\]+)",
    re.I,
)
_REJECT_SUBSTRS = ("punish", "passport.", "bixi.alicdn", "mini_login", "captcha")
_AUTH_RET_HINTS = (
    "rgv587_error",
    "fail_sys_user_validate",
    "fail_sys_session_expired",
    "fail_sys_illegal_access",
    "session过期",
    "请登录",
)
_TOKEN_RETRY_HINTS = (
    "fail_sys_token_empty",
    "fail_sys_token_expired",
    "fail_sys_token_exoired",
    "令牌过期",
    "令牌为空",
)

_LOCK = threading.Lock()


def _norm_http(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    u = value.strip()
    if u.startswith("//"):
        u = "https:" + u
    elif u.startswith("http://"):
        u = "https://" + u[len("http://") :]
    if not u.startswith("https://"):
        return None
    low = u.lower()
    if any(bad in low for bad in _REJECT_SUBSTRS):
        return None
    return u


def _looks_media(url: str | None) -> bool:
    if not url:
        return False
    low = url.lower()
    if any(bad in low for bad in _REJECT_SUBSTRS):
        return False
    return (
        ".mp4" in low
        or ".m3u8" in low
        or "cloud.video.taobao.com" in low
        or "tbvideo" in low
        or "video.goofish" in low
    )


def _walk_media_url(obj: Any, _depth: int = 0) -> str | None:
    if _depth > 12:
        return None
    if isinstance(obj, str):
        u = _norm_http(obj)
        if u and _looks_media(u):
            return u
        return None
    if isinstance(obj, dict):
        for key in ("video_url", "playUrl", "videoUrl", "videoPlayUrl", "hdUrl", "highUrl", "url"):
            if key in obj:
                u = _norm_http(obj.get(key))
                if u and _looks_media(u):
                    return u
        for v in obj.values():
            found = _walk_media_url(v, _depth + 1)
            if found:
                return found
    elif isinstance(obj, list):
        for item in obj[:40]:
            found = _walk_media_url(item, _depth + 1)
            if found:
                return found
    return None


def _is_auth_or_risk(payload: Any) -> bool:
    if not isinstance(payload, dict):
        return False
    ret = " ".join(str(x) for x in (payload.get("ret") or [])).lower()
    if any(h in ret for h in _AUTH_RET_HINTS):
        return True
    data = payload.get("data")
    if isinstance(data, dict):
        u = str(data.get("url") or "").lower()
        if any(bad in u for bad in ("punish", "passport.goofish", "mini_login")):
            return True
    return False


def _needs_token_retry(payload: Any) -> bool:
    if not isinstance(payload, dict):
        return False
    ret = " ".join(str(x) for x in (payload.get("ret") or [])).lower()
    return any(h in ret for h in _TOKEN_RETRY_HINTS)


def _mtop_sign(token: str, ts: str, app_key: str, data_str: str) -> str:
    raw = f"{token}&{ts}&{app_key}&{data_str}"
    return hashlib.md5(raw.encode("utf-8")).hexdigest()


def parse_video_query(payload: Any) -> str | None:
    """Extract HD video URL from `mtop.idle.cloud.video.query` response."""
    if not isinstance(payload, dict):
        return None
    data = payload.get("data")
    if not isinstance(data, dict):
        return None
    cdn = data.get("cdnVideo")
    if isinstance(cdn, dict):
        resources = cdn.get("resources")
        if isinstance(resources, list):
            for res in resources:
                if isinstance(res, dict):
                    u = _norm_http(res.get("video_url") or res.get("url") or res.get("playUrl"))
                    if u:
                        return u
    return _walk_media_url(data)


def parse_xianyu_detail(payload: Any, page_url: str) -> dict | None:
    """Parse `mtop.taobao.idle.pc.detail` or `awesome.detail` JSON payload."""
    if not isinstance(payload, dict):
        return None
    if _is_auth_or_risk(payload):
        return {
            "ok": False,
            "needs_cookie": True,
            "error": "闲鱼需要登录 Cookie 或触发风控验证",
        }
    data = payload.get("data")
    if not isinstance(data, dict):
        return None
    item_do = data.get("itemDO") or data.get("itemDo") or data.get("item")
    if not isinstance(item_do, dict):
        return None

    raw_title = (
        item_do.get("title")
        or item_do.get("desc")
        or item_do.get("richTextDesc")
        or "xianyu"
    )
    title = str(raw_title).strip().splitlines()[0][:120] if str(raw_title).strip() else "xianyu"

    vpi = (
        item_do.get("videoPlayInfo")
        or item_do.get("videoInfo")
        or item_do.get("itemVideoDO")
        or {}
    )
    if not isinstance(vpi, dict):
        vpi = {}

    direct = _norm_http(
        vpi.get("playUrl")
        or vpi.get("videoUrl")
        or vpi.get("video_url")
        or vpi.get("play_url")
        or vpi.get("hdUrl")
        or vpi.get("highUrl")
        or vpi.get("sd480pUrl")
        or vpi.get("ld320pUrl")
    )
    mts_vid = vpi.get("mtsVid") or vpi.get("videoId") or item_do.get("videoId")
    mts_vid = str(mts_vid).strip() if mts_vid else None

    thumb = _norm_http(vpi.get("url") or vpi.get("coverUrl") or vpi.get("cover"))
    image_infos = item_do.get("imageInfos") or []
    if isinstance(image_infos, list):
        for img in image_infos:
            if not isinstance(img, dict):
                continue
            if not thumb:
                thumb = _norm_http(img.get("url"))
            if not direct:
                cand = _norm_http(img.get("videoUrl") or img.get("playUrl"))
                if cand:
                    direct = cand
            if not mts_vid and img.get("mtsVid"):
                mts_vid = str(img["mtsVid"]).strip()

    if not direct:
        direct = _walk_media_url(item_do)

    if not direct and not mts_vid:
        # Valid itemDO fetched, but this listing has no video (images only).
        if item_do.get("itemId") or item_do.get("title") or item_do.get("desc") or image_infos:
            return {
                "ok": False,
                "no_video": True,
                "error": "该闲鱼商品没有包含视频（仅图片）",
            }
        return None

    return {
        "ok": bool(direct),
        "title": title,
        "ext": "m3u8" if (direct and ".m3u8" in direct.lower()) else "mp4",
        "filesize": None,
        "thumbnail": thumb,
        "extractor": NAME,
        "extractor_key": "Xianyu",
        "platform": platform_of(page_url, "Xianyu"),
        "direct_url": direct,
        "needs_merge": bool(direct and ".m3u8" in direct.lower()),
        "page_url": page_url,
        "http_headers": {
            "User-Agent": UA,
            "Referer": "https://www.goofish.com/",
        },
        "_mts_vid": mts_vid,
    }


def _build_ok(parsed: dict) -> dict:
    out = dict(parsed)
    out.pop("_mts_vid", None)
    out["ok"] = True
    direct = out.get("direct_url") or ""
    ext = "m3u8" if ".m3u8" in direct.lower() else "mp4"
    out["ext"] = ext
    out["needs_merge"] = ext == "m3u8"
    return out


def _call_mtop(
    client: httpx.Client,
    api: str,
    version: str,
    app_key: str,
    data_dict: dict,
    timeout: float = 8.0,
) -> dict | None:
    endpoint = f"https://h5api.m.goofish.com/h5/{api}/{version}/"
    data_str = json.dumps(data_dict, separators=(",", ":"), ensure_ascii=False)
    for attempt in range(2):
        tk = client.cookies.get("_m_h5_tk") or ""
        token = tk.split("_", 1)[0] if tk else ""
        ts = str(int(time.time() * 1000))
        sign = _mtop_sign(token, ts, app_key, data_str) if token else "0"
        params = {
            "jsv": "2.7.2",
            "appKey": app_key,
            "t": ts,
            "sign": sign,
            "v": version,
            "type": "originaljson",
            "dataType": "json",
            "api": api,
        }
        try:
            resp = client.post(endpoint, params=params, data={"data": data_str}, timeout=timeout)
            payload = resp.json()
        except Exception as exc:
            log.info("xianyu mtop %s fail: %s", api, str(exc)[:100])
            return None
        if attempt == 0 and (_needs_token_retry(payload) or not token):
            # If server issued a fresh _m_h5_tk cookie, retry once with signed request.
            new_tk = client.cookies.get("_m_h5_tk") or ""
            if new_tk and new_tk != tk:
                continue
        return payload if isinstance(payload, dict) else None
    return None


def _extract_via_mtop(item_id: str, page_url: str, user_cookies: list[dict]) -> dict | None:
    from ..config import MOBILE_UA

    jar = httpx.Cookies()
    for c in user_cookies:
        try:
            jar.set(c["name"], c["value"], domain=c.get("domain") or ".goofish.com", path=c.get("path") or "/")
        except Exception:
            jar.set(c["name"], c["value"])

    # Guest mode prefers H5 share API (mtop.taobao.idle.awesome.detail) which works without login on residential IPs;
    # authenticated mode prefers PC detail API which supports UD/HD upgrade via mtop.idle.cloud.video.query.
    pc_plan = (
        MTOP_DETAIL_PC,
        MTOP_APP_KEY_PC,
        {"User-Agent": UA, "Referer": "https://www.goofish.com/", "Origin": "https://www.goofish.com", "Accept": "application/json"},
    )
    h5_plan = (
        MTOP_DETAIL_H5,
        MTOP_APP_KEY_H5,
        {"User-Agent": MOBILE_UA, "Referer": "https://h5.m.goofish.com/", "Origin": "https://h5.m.goofish.com", "Accept": "application/json"},
    )
    plans = (pc_plan, h5_plan) if user_cookies else (h5_plan, pc_plan)

    last_auth_fail: dict | None = None
    for api, app_key, headers in plans:
        with httpx.Client(follow_redirects=True, headers=headers, cookies=jar, timeout=8.0) as client:
            payload = _call_mtop(client, api, "1.0", app_key, {"itemId": item_id})
            parsed = parse_xianyu_detail(payload, page_url)
            if parsed is None:
                continue
            if parsed.get("no_video"):
                return parsed
            if parsed.get("needs_cookie"):
                last_auth_fail = parsed
                continue

            mts_vid = parsed.get("_mts_vid")
            if mts_vid:
                vq = _call_mtop(
                    client,
                    MTOP_VIDEO_QUERY,
                    "1.0",
                    MTOP_APP_KEY_PC,
                    {
                        "videoId": mts_vid,
                        "expectedDefPriority": "ud#hd#sd#ld",
                        "from": "xianyuPC",
                        "playScenes": "xianyu",
                        "expectedCodec": "h264",
                    },
                )
                hd_url = parse_video_query(vq)
                if hd_url:
                    parsed["direct_url"] = hd_url

            if parsed.get("direct_url"):
                return _build_ok(parsed)
    return last_auth_fail


def _extract_via_browser(item_id: str, page_url: str, user_cookies: list[dict]) -> dict | None:
    try:
        from playwright.sync_api import sync_playwright
    except Exception:
        return None
    from .douyin_browser import CHROME_ARGS, IPHONE_UA, _launch_browser

    deadline = time.monotonic() + min(BROWSER_TIMEOUT, 18.0)
    saw_auth_block = False

    # Guest mode tries Mobile H5 share page first (works anonymously on non-datacenter IPs), then PC.
    # Logged-in mode tries PC first (captures UD/HD cloud.video.query), then Mobile H5.
    h5_url = f"https://h5.m.goofish.com/item?id={item_id}"
    pc_target = (page_url, False)
    h5_target = (h5_url, True)
    targets = (pc_target, h5_target) if user_cookies else (h5_target, pc_target)

    with _LOCK:
        try:
            with sync_playwright() as p:
                browser = _launch_browser(p)
                try:
                    for target_url, is_mobile in targets:
                        if time.monotonic() >= deadline - 2.0:
                            break
                        captured_detail: dict | None = None
                        captured_hd: str | None = None
                        captured_media: list[str] = []
                        target_auth_block = False

                        ctx = browser.new_context(
                            locale="zh-CN",
                            timezone_id="Asia/Shanghai",
                            viewport={"width": 390, "height": 844} if is_mobile else {"width": 1280, "height": 800},
                            user_agent=IPHONE_UA if is_mobile else UA,
                            is_mobile=is_mobile,
                            has_touch=is_mobile,
                            extra_http_headers={"Accept-Language": "zh-CN,zh;q=0.9"},
                        )
                        try:
                            ctx.add_init_script(
                                "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"
                            )
                            if user_cookies:
                                pw_cookies = [
                                    {
                                        "name": c["name"],
                                        "value": c["value"],
                                        "domain": c.get("domain") or ".goofish.com",
                                        "path": c.get("path") or "/",
                                    }
                                    for c in user_cookies
                                ]
                                try:
                                    ctx.add_cookies(pw_cookies)
                                except Exception as exc:
                                    log.info("xianyu browser add_cookies skip: %s", str(exc)[:100])

                            page = ctx.new_page()

                            def on_response(resp):
                                nonlocal captured_detail, captured_hd, target_auth_block, saw_auth_block
                                try:
                                    u = resp.url or ""
                                    low = u.lower()
                                    if MTOP_VIDEO_QUERY in low:
                                        hd = parse_video_query(resp.json())
                                        if hd:
                                            captured_hd = hd
                                        return
                                    if MTOP_DETAIL_PC in low or MTOP_DETAIL_H5 in low:
                                        parsed = parse_xianyu_detail(resp.json(), page_url)
                                        if parsed:
                                            if parsed.get("needs_cookie"):
                                                target_auth_block = True
                                                saw_auth_block = True
                                            else:
                                                captured_detail = parsed
                                        return
                                    ct = ""
                                    try:
                                        ct = (resp.headers.get("content-type") or "").lower()
                                    except Exception:
                                        ct = ""
                                    if "video/" in ct or _looks_media(u):
                                        norm = _norm_http(u)
                                        if norm and norm not in captured_media:
                                            captured_media.append(norm)
                                except Exception:
                                    pass

                            page.on("response", on_response)
                            remain_ms = int(max(3.0, min(8.0, deadline - time.monotonic())) * 1000)
                            try:
                                page.goto(target_url, wait_until="domcontentloaded", timeout=remain_ms)
                            except Exception as exc:
                                log.info("xianyu browser goto fail %s: %s", target_url, str(exc)[:100])
                                continue

                            wait_until = min(deadline, time.monotonic() + 5.0)
                            while time.monotonic() < wait_until:
                                if captured_hd and captured_detail and captured_detail.get("ok"):
                                    captured_detail["direct_url"] = captured_hd
                                    return _build_ok(captured_detail)
                                if captured_detail and captured_detail.get("no_video"):
                                    return captured_detail
                                if captured_detail and captured_detail.get("direct_url") and not captured_detail.get("_mts_vid"):
                                    return _build_ok(captured_detail)
                                if target_auth_block and not captured_detail and not captured_media:
                                    # Stop early on Baxia/login gate — never attempt CAPTCHA bypass.
                                    break
                                try:
                                    vsrc = page.evaluate(
                                        """() => {
                                            const v = document.querySelector('video');
                                            if (v && v.currentSrc && v.currentSrc.startsWith('http')) return v.currentSrc;
                                            if (v && v.src && v.src.startsWith('http')) return v.src;
                                            const s = document.querySelector('video source');
                                            return (s && s.src && s.src.startsWith('http')) ? s.src : '';
                                        }"""
                                    )
                                    norm = _norm_http(vsrc)
                                    if norm and norm not in captured_media:
                                        captured_media.append(norm)
                                except Exception:
                                    pass
                                page.wait_for_timeout(250)

                            if captured_detail:
                                if captured_hd:
                                    captured_detail["direct_url"] = captured_hd
                                if captured_detail.get("direct_url"):
                                    return _build_ok(captured_detail)
                                if captured_detail.get("no_video"):
                                    return captured_detail

                            if captured_media:
                                try:
                                    dom_title = (page.title() or "").replace(" - 闲鱼", "").strip()
                                except Exception:
                                    dom_title = ""
                                return _build_ok(
                                    {
                                        "ok": True,
                                        "title": dom_title or f"xianyu_{item_id}",
                                        "thumbnail": None,
                                        "extractor": NAME,
                                        "extractor_key": "Xianyu",
                                        "platform": platform_of(page_url, "Xianyu"),
                                        "direct_url": captured_media[0],
                                        "page_url": page_url,
                                        "http_headers": {
                                            "User-Agent": UA,
                                            "Referer": "https://www.goofish.com/",
                                        },
                                    }
                                )
                        finally:
                            ctx.close()
                finally:
                    browser.close()
        except Exception as exc:
            log.info("xianyu browser fail: %s", str(exc)[:160])

    if saw_auth_block:
        return {
            "ok": False,
            "needs_cookie": True,
            "error": "闲鱼触发风控或需要登录 Cookie（云机 IP 常被风控）",
        }
    return None


class XianyuEngine:
    name = NAME
    stage = STAGE

    def available(self) -> bool:
        return True

    def version(self) -> str:
        return "mtop+browser"

    def matches(self, url: str) -> bool:
        return is_xianyu(url)

    def extract(self, url: str) -> dict:
        target = url
        item_id = xianyu_item_id_of(target)
        if not item_id:
            target = expand_share_url(url)
            item_id = xianyu_item_id_of(target)
        if not item_id:
            return {"ok": False, "error": "未能从闲鱼链接中识别商品 ID"}

        page_url = f"https://www.goofish.com/item?id={item_id}"
        user_cookies = cookies_for_domains(XIANYU_COOKIE_DOMAINS, current())

        # 1) Fast path: direct MTOP API
        mtop_res = _extract_via_mtop(item_id, page_url, user_cookies)
        if mtop_res and mtop_res.get("ok"):
            log.info("xianyu mtop ok item_id=%s", item_id)
            return mtop_res
        if mtop_res and mtop_res.get("no_video"):
            return {"ok": False, "error": mtop_res["error"]}

        # 2) Fallback: passive Playwright capture (with user cookies if supplied)
        browser_res = _extract_via_browser(item_id, page_url, user_cookies)
        if browser_res and browser_res.get("ok"):
            log.info("xianyu browser ok item_id=%s", item_id)
            return browser_res
        if browser_res and browser_res.get("no_video"):
            return {"ok": False, "error": browser_res["error"]}

        if (mtop_res and mtop_res.get("needs_cookie")) or (browser_res and browser_res.get("needs_cookie")):
            return {
                "ok": False,
                "needs_cookie": True,
                "error": "闲鱼商品详情需要登录 Cookie（当前匿名请求被风控拦截）",
            }
        return {"ok": False, "error": "未能从闲鱼商品页提取到视频"}
