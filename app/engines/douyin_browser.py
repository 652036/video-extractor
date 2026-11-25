"""Douyin headless Chrome engine (stage 15).

Reproduces a proven guest Chrome session:
  https://www.iesdouyin.com/share/video/{aweme_id}/  (trailing slash REQUIRED)
redirects to www.douyin.com/video/{id}; <video> plays muted behind a login QR.
We never click the login modal.

<video> src is an MSE blob. Real media is in network / Performance
(host like v5-dy-ov-experiment.zjcdn.com, mime_type=video_mp4) or in
mobile share-page SSR play_addr after Chrome passes the WAF challenge.

No user cookies, no VIP/DRM, no login.
"""

from __future__ import annotations

import logging
import threading
import time
from urllib.parse import urlparse

import httpx

from ..config import MOBILE_UA, ROOT, UA
from ..urls import aweme_id_of, is_douyin
from ..util import platform_of

log = logging.getLogger("unwatermark")

NAME = "douyin-browser"
STAGE = 15
TIMEOUT_S = 25.0
PROFILE_DIR = ROOT / ".pw-douyin"

CDN_HOSTS = ("zjcdn", "douyinvod", "bytecdn", "snssdk.com", "bytecdn.cn")
REJECT_HOSTS = ("douyinstatic.com", "byteimg.com", "douyinpic.com", "douyincdn.com")

RANGE_HEADERS = {
    "User-Agent": MOBILE_UA,
    "Referer": "https://www.douyin.com/",
    "Range": "bytes=0-2047",
    "Accept": "*/*",
}

CHROME_ARGS = [
    "--no-sandbox",
    "--disable-dev-shm-usage",
    "--autoplay-policy=no-user-gesture-required",
    "--mute-audio",
    "--disable-blink-features=AutomationControlled",
    "--disable-features=Translate,MediaRouter",
]

IPHONE_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 "
    "Mobile/15E148 Safari/604.1"
)

_LOCK = threading.Lock()


def _share_url(url: str) -> str:
    aid = aweme_id_of(url)
    if aid:
        return f"https://www.iesdouyin.com/share/video/{aid}/"
    return url if url.endswith("/") else url + "/"


def _looks_video(url: str, content_type: str = "") -> bool:
    if not url or not url.startswith("http"):
        return False
    low = url.lower()
    if low.startswith(("blob:", "data:", "javascript:")):
        return False
    host = (urlparse(url).hostname or "").lower()
    if any(h in host for h in REJECT_HOSTS):
        return False
    if "/aweme/v1/web/" in low or "/web/aweme/detail" in low:
        return False
    if "mime_type=video_mp4" in low or "mime_type=video_webm" in low:
        return True
    if any(h in host or h in low for h in CDN_HOSTS) and (
        "play" in low or "video" in low or "mime_type=" in low or "tos-cn-v-" in low
    ):
        if "/aweme/v1/play" in low or "tos-cn-v-" in low or any(
            h in host for h in ("zjcdn", "douyinvod", "bytecdn")
        ):
            return True
    if "/aweme/v1/play" in low:
        return True
    if "tos-cn-v-" in low and ("video" in low or ".mp4" in low):
        return True
    ct = (content_type or "").lower()
    if "video/mp4" in ct and any(h in host for h in ("zjcdn", "douyinvod", "bytecdn", "snssdk")):
        return True
    return False


def _rank(url: str) -> tuple:
    low = url.lower()
    watermarked = 1 if "playwm" in low else 0
    preferred = 0
    if "mime_type=video_mp4" in low:
        preferred -= 3
    if any(h in low for h in ("zjcdn", "tos-cn-v-", "douyinvod")):
        preferred -= 2
    if "/aweme/v1/play" in low and "playwm" not in low:
        preferred -= 1
    return (watermarked, preferred, len(url))


def _is_mp4(status: int, content_type: str, body: bytes) -> bool:
    if status not in (200, 206):
        return False
    ct = (content_type or "").lower()
    if "json" in ct or "text/" in ct or "html" in ct:
        return False
    if "video/" in ct:
        return True
    blob = body or b""
    return blob[4:8] == b"ftyp" or blob[:3] == b"\x1aE\xdf"


def _verify_httpx(url: str) -> bool:
    try:
        with httpx.Client(follow_redirects=True, timeout=8.0, headers=RANGE_HEADERS) as client:
            with client.stream("GET", url) as resp:
                buf = bytearray()
                for chunk in resp.iter_bytes(2048):
                    buf.extend(chunk)
                    if len(buf) >= 2048:
                        break
                status = resp.status_code
                ct = resp.headers.get("content-type") or ""
        ok = _is_mp4(status, ct, bytes(buf[:2048]))
        log.info(
            "douyin-browser range status=%s ct=%s host=%s ok=%s",
            status,
            ct[:32],
            urlparse(url).hostname,
            ok,
        )
        return ok
    except Exception as exc:
        log.info("douyin-browser range fail host=%s err=%s", urlparse(url).hostname, str(exc)[:80])
        return False


def _verify_page(page, url: str) -> bool:
    try:
        resp = page.request.get(
            url,
            headers={
                "User-Agent": MOBILE_UA,
                "Referer": "https://www.douyin.com/",
                "Range": "bytes=0-2047",
                "Accept": "*/*",
            },
            timeout=8000,
        )
        body = b""
        try:
            body = resp.body() or b""
        except Exception:
            body = b""
        ok = _is_mp4(resp.status, resp.headers.get("content-type") or "", body)
        log.info("douyin-browser page-range status=%s host=%s ok=%s", resp.status, urlparse(url).hostname, ok)
        return ok
    except Exception as exc:
        log.info("douyin-browser page-range fail host=%s err=%s", urlparse(url).hostname, str(exc)[:80])
        return False


def _verify(url: str, page=None) -> bool:
    if _verify_httpx(url):
        return True
    if page is not None:
        return _verify_page(page, url)
    return False


def _collect_perf(page) -> list[str]:
    try:
        names = page.evaluate(
            """() => performance.getEntriesByType('resource').map(e => e.name || '')"""
        )
    except Exception:
        return []
    return [n for n in (names or []) if isinstance(n, str) and _looks_video(n)]


def _nudge_play(page) -> None:
    try:
        page.evaluate(
            """() => {
                document.querySelectorAll('video').forEach(v => {
                    try {
                        v.muted = true;
                        v.defaultMuted = true;
                        v.volume = 0;
                        const p = v.play();
                        if (p && p.catch) p.catch(() => {});
                    } catch (e) {}
                });
            }"""
        )
    except Exception:
        pass


def _harvest_html_play(page) -> tuple[list[str], str | None, str | None]:
    try:
        html = page.content() or ""
    except Exception:
        return [], None, None
    try:
        from .sharepage import _dewatermark, _parse_douyin_html

        parsed = _parse_douyin_html(page.url or "", html)
    except Exception:
        return [], None, None
    if not parsed:
        return [], None, None
    title = parsed.get("title")
    thumb = parsed.get("thumbnail")
    direct = parsed.get("direct_url")
    if not direct:
        return [], title, thumb
    direct = _dewatermark(direct)
    return [direct], title, thumb


def _title_of(page) -> str:
    try:
        title = page.evaluate(
            """() => {
                const og = document.querySelector('meta[property="og:title"]');
                if (og && og.content) return og.content;
                return document.title || '';
            }"""
        )
    except Exception:
        title = ""
    title = (title or "").strip()
    for suffix in (" - 抖音", " - Douyin", "_抖音"):
        if title.endswith(suffix):
            title = title[: -len(suffix)].strip()
    return title or "douyin"


def _ok(url: str, *, title: str, direct: str, page: str, thumb: str | None = None) -> dict:
    return {
        "ok": True,
        "title": title or "douyin",
        "ext": "mp4",
        "filesize": None,
        "thumbnail": thumb if isinstance(thumb, str) else None,
        "extractor": NAME,
        "extractor_key": "DouyinBrowser",
        "platform": platform_of(url, "Douyin"),
        "direct_url": direct,
        "needs_merge": False,
        "page_url": page or url,
    }


class DouyinBrowserEngine:
    name = NAME
    stage = STAGE

    def available(self) -> bool:
        try:
            from playwright.sync_api import sync_playwright  # noqa: F401
        except Exception:
            return False
        return True

    def version(self) -> str:
        try:
            from importlib.metadata import version

            return f"playwright-{version('playwright')}"
        except Exception:
            return "playwright+chrome"

    def matches(self, url: str) -> bool:
        return is_douyin(url)

    def extract(self, url: str) -> dict:
        if not is_douyin(url):
            return {"ok": False, "error": "douyin-browser 仅处理抖音"}
        with _LOCK:
            return _extract_locked(url)


def _launch_browser(p):
    try:
        return p.chromium.launch(channel="chrome", headless=True, args=CHROME_ARGS)
    except Exception as exc:
        log.info("douyin-browser chrome channel failed: %s; trying bundled chromium", exc)
        return p.chromium.launch(headless=True, args=CHROME_ARGS)


def _extract_locked(url: str) -> dict:
    from playwright.sync_api import sync_playwright

    aid = aweme_id_of(url)
    target = _share_url(url)
    candidates: list[str] = []
    seen: set[str] = set()
    failed_verify: set[str] = set()
    title = "douyin"
    thumb: str | None = None
    page_url = target
    deadline = time.monotonic() + TIMEOUT_S

    def add(u: str, content_type: str = "") -> None:
        if not u or u in seen:
            return
        if not _looks_video(u, content_type):
            return
        seen.add(u)
        candidates.append(u)
        log.info("douyin-browser candidate host=%s", urlparse(u).hostname)

    def attach(page):
        def on_request(req):
            try:
                add(req.url)
            except Exception:
                pass

        def on_response(resp):
            try:
                ct = ""
                try:
                    ct = resp.headers.get("content-type") or ""
                except Exception:
                    ct = ""
                add(resp.url, ct)
            except Exception:
                pass

        page.on("request", on_request)
        page.on("response", on_response)

    def try_candidates(page) -> dict | None:
        nonlocal title
        for cand in sorted(candidates, key=_rank):
            if cand in failed_verify:
                continue
            if _verify(cand, page):
                title = title if title and title != "douyin" else _title_of(page)
                log.info("douyin-browser ok host=%s title=%s", urlparse(cand).hostname, (title or "")[:40])
                return _ok(url, title=title, direct=cand, page=page.url or page_url, thumb=thumb)
            failed_verify.add(cand)
        return None

    def check_html(page) -> dict | None:
        nonlocal title, thumb
        extras, html_title, html_thumb = _harvest_html_play(page)
        if html_title:
            title = html_title
        if html_thumb:
            thumb = html_thumb
        for extra in extras:
            add(extra)
            if extra not in failed_verify and _verify(extra, page):
                log.info("douyin-browser html-play ok host=%s", urlparse(extra).hostname)
                return _ok(url, title=title, direct=extra, page=page.url or page_url, thumb=thumb)
            failed_verify.add(extra)
        return None

    last_err = "未捕获到视频地址"
    try:
        with sync_playwright() as p:
            browser = _launch_browser(p)
            try:
                # 1) Mobile share flow first: www.douyin.com/video/{id} passes WAF and redirects
                # to m.douyin.com/share/video/{id} whose SSR HTML immediately embeds play_addr.
                mobile = browser.new_context(
                    locale="zh-CN",
                    timezone_id="Asia/Shanghai",
                    viewport={"width": 390, "height": 844},
                    user_agent=IPHONE_UA,
                    is_mobile=True,
                    has_touch=True,
                    extra_http_headers={"Accept-Language": "zh-CN,zh;q=0.9"},
                )
                try:
                    mobile.add_init_script(
                        "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"
                    )
                    mpage = mobile.new_page()
                    attach(mpage)
                    mobile_targets = []
                    if aid:
                        mobile_targets.append(f"https://www.douyin.com/video/{aid}")
                    mobile_targets.append(target)
                    for mt in mobile_targets:
                        if time.monotonic() >= deadline - 1.5:
                            break
                        try:
                            mpage.goto(
                                mt,
                                wait_until="domcontentloaded",
                                timeout=int(max(3.0, min(10.0, deadline - time.monotonic())) * 1000),
                            )
                        except Exception as exc:
                            log.info("douyin-browser mobile goto fail %s: %s", mt, exc)
                            continue
                        page_url = mpage.url or page_url
                        mobile_until = min(deadline, time.monotonic() + 5.0)
                        while time.monotonic() < mobile_until:
                            t = _title_of(mpage)
                            if t and t != "douyin":
                                title = t
                            hit = check_html(mpage)
                            if hit:
                                return hit
                            for name in _collect_perf(mpage):
                                add(name)
                            hit = try_candidates(mpage)
                            if hit:
                                return hit
                            mpage.wait_for_timeout(250)
                finally:
                    mobile.close()

                # 2) Desktop fallback: iesdouyin trailing-slash -> www.douyin.com/video.
                if time.monotonic() < deadline - 2.0:
                    desktop = browser.new_context(
                        locale="zh-CN",
                        timezone_id="Asia/Shanghai",
                        viewport={"width": 1280, "height": 800},
                        user_agent=UA,
                        extra_http_headers={"Accept-Language": "zh-CN,zh;q=0.9"},
                    )
                    try:
                        desktop.add_init_script(
                            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"
                        )
                        dpage = desktop.new_page()
                        attach(dpage)
                        remain_ms = int(max(3.0, min(10.0, deadline - time.monotonic())) * 1000)
                        dpage.goto(target, wait_until="domcontentloaded", timeout=remain_ms)
                        try:
                            dpage.wait_for_url("**/*douyin.com/video/**", timeout=4000)
                        except Exception:
                            pass
                        page_url = dpage.url or target
                        t = _title_of(dpage)
                        if t and t != "douyin":
                            title = t
                        # Login QR may appear. Do not click, dismiss, or fill it.
                        desktop_until = min(deadline, time.monotonic() + 6.0)
                        while time.monotonic() < desktop_until:
                            _nudge_play(dpage)
                            for name in _collect_perf(dpage):
                                add(name)
                            hit = check_html(dpage)
                            if hit:
                                return hit
                            hit = try_candidates(dpage)
                            if hit:
                                return hit
                            dpage.wait_for_timeout(300)
                    finally:
                        desktop.close()
                last_err = f"超时未拿到可播放地址（候选 {len(candidates)}）"
            finally:
                browser.close()
    except Exception as exc:
        last_err = str(exc)[:200]
        log.info("douyin-browser fail: %s", last_err)

    for cand in sorted(candidates, key=_rank):
        if cand in failed_verify:
            continue
        if _verify(cand):
            return _ok(url, title=title, direct=cand, page=page_url, thumb=thumb)
    return {"ok": False, "error": last_err}
