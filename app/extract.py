"""URL parse + staged multi-engine race. First success wins; others cancelled."""

from __future__ import annotations

import asyncio
import logging
import time

from .config import BROWSER_TIMEOUT, CN_OVERALL_TIMEOUT, ENGINE_TIMEOUT, OVERALL_TIMEOUT, SHARE_TIMEOUT
from .engines import load_engines
from .urls import expand_share_url, is_cn_short_video, is_douyin, is_xianyu, ytdlp_url
from .util import extract_url, hostname_of, is_blocked, platform_of

log = logging.getLogger("video_extractor")

_ENGINES = None

SHARE_STAGE = 10
BROWSER_STAGE = 15
CORE_STAGE = 20
FALLBACK_STAGE = 30
WEBPARSER_MIN_S = 15.0
BROWSER_MIN_S = BROWSER_TIMEOUT


def get_engines():
    global _ENGINES
    if _ENGINES is None:
        _ENGINES = load_engines()
    return _ENGINES


def engines_status() -> list[dict]:
    out = []
    for e in get_engines():
        try:
            avail = bool(e.available())
            ver = e.version() if avail else None
        except Exception:
            avail, ver = False, None
        out.append({"name": e.name, "available": avail, "version": ver})
    return out


def normalize_input(text: str) -> tuple[str | None, str | None]:
    url = extract_url(text or "")
    if not url:
        return None, "未找到有效链接，请粘贴含 http(s) 的分享口令或网址"
    blocked = is_blocked(url)
    if blocked:
        return None, f"不支持会员/DRM 平台：{blocked}"
    return url, None


def _stage_of(engine) -> int:
    return int(getattr(engine, "stage", CORE_STAGE))


def _matches(engine, url: str) -> bool:
    fn = getattr(engine, "matches", None)
    if fn is None:
        return True
    try:
        return bool(fn(url))
    except Exception:
        return True


def _has_engine(group: list, name: str) -> bool:
    return any(getattr(e, "name", "") == name for e in group)


async def _run_engine(engine, url: str, timeout: float) -> dict:
    return await asyncio.wait_for(asyncio.to_thread(engine.extract, url), timeout=timeout)


async def _race(engines: list, url: str, deadline: float, per_timeout: float) -> tuple[dict | None, list[str], bool]:
    errors: list[str] = []
    needs_cookie = False
    if not engines:
        return None, errors, needs_cookie

    async def run(engine):
        remain = min(per_timeout, max(0.5, deadline - time.monotonic()))
        return await _run_engine(engine, url, remain)

    tasks: dict[asyncio.Task, str] = {}
    for engine in engines:
        tasks[asyncio.create_task(run(engine), name=engine.name)] = engine.name

    winner = None
    try:
        pending: set[asyncio.Task] = set(tasks)
        while pending:
            remain = deadline - time.monotonic()
            if remain <= 0:
                errors.append("整体超时")
                break
            done, pending = await asyncio.wait(pending, timeout=remain, return_when=asyncio.FIRST_COMPLETED)
            if not done:
                errors.append("整体超时")
                break
            for task in done:
                name = tasks.get(task, task.get_name())
                try:
                    result = task.result()
                except asyncio.TimeoutError:
                    errors.append(f"{name}: 超时")
                    continue
                except asyncio.CancelledError:
                    continue
                except Exception as exc:
                    errors.append(f"{name}: {str(exc)[:160]}")
                    continue
                if result and result.get("ok"):
                    winner = result
                    for p in pending:
                        p.cancel()
                    pending.clear()
                    break
                if result and result.get("needs_cookie"):
                    needs_cookie = True
                    errors.append(f"{name}: needs cookie")
                else:
                    errors.append(f"{name}: {(result or {}).get('error', '失败')}")
            if winner:
                break
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
    return winner, errors, needs_cookie


async def extract_media(url: str) -> dict:
    engines = [e for e in get_engines() if e.available()]
    if not engines:
        return {"ok": False, "error": "没有可用的解析引擎"}

    host = hostname_of(url)
    errors: list[str] = []
    needs_cookie = False

    resolved = url
    try:
        resolved = await asyncio.wait_for(asyncio.to_thread(expand_share_url, url), timeout=12.0)
    except Exception as exc:
        errors.append(f"短链展开: {str(exc)[:80]}")
        resolved = url

    blocked = is_blocked(resolved)
    if blocked:
        return {"ok": False, "error": f"不支持会员/DRM 平台：{blocked}"}

    cn = is_cn_short_video(url) or is_cn_short_video(resolved)
    douyin = is_douyin(url) or is_douyin(resolved)
    overall = CN_OVERALL_TIMEOUT if cn else OVERALL_TIMEOUT
    deadline = time.monotonic() + overall

    race_url = ytdlp_url(url, resolved)

    def matches_any(engine) -> bool:
        return _matches(engine, url) or (resolved != url and _matches(engine, resolved))

    named_fallback = {"webparser", "lux"}
    named_share = {"share-page"}
    named_browser = {"douyin-browser", "xianyu"}
    share = [e for e in engines if (e.name in named_share or _stage_of(e) <= SHARE_STAGE) and matches_any(e)]
    browser = [
        e
        for e in engines
        if (e.name in named_browser or _stage_of(e) == BROWSER_STAGE) and matches_any(e)
    ]
    fallback = [e for e in engines if e.name in named_fallback and matches_any(e)]
    assigned = set(share) | set(browser) | set(fallback)
    core = [e for e in engines if e not in assigned and matches_any(e)]

    # Douyin/Kuaishou: race share-page + public parsers + Chrome guest fetch
    # in the FIRST stage so yt-dlp's needs_cookie cannot eat the budget.
    first = list(share)
    if cn:
        for e in fallback:
            if e not in first:
                first.append(e)
        for e in browser:
            if e not in first:
                first.insert(0, e)

    stages: list[tuple[list, str, float, str]] = []
    first_budget = max(SHARE_TIMEOUT, WEBPARSER_MIN_S + 1.0)
    if browser and first and _has_engine(first, "douyin-browser"):
        first_budget = max(first_budget, BROWSER_MIN_S)
    if cn and first:
        stages.append((first, resolved, first_budget, "first"))
    # Dedicated browser/site-specific stage if it was not already in first.
    if browser and not (cn and first and _has_engine(first, "douyin-browser")):
        stages.append((browser, resolved, BROWSER_MIN_S, "browser"))
    xianyu_only = (is_xianyu(url) or is_xianyu(resolved)) and _has_engine(browser, "xianyu")
    if core and not xianyu_only:
        stages.append((core, race_url, ENGINE_TIMEOUT, "core"))
    # Only schedule a separate post-core fallback stage if fallback did not already run in first.
    if cn and fallback and not any(e in first for e in fallback):
        stages.append((fallback, resolved, WEBPARSER_MIN_S + 1.0, "fallback"))

    webparser_budget = 0.0
    browser_budget = 0.0

    async def run_group(group: list, target: str, per: float) -> dict | None:
        nonlocal deadline, needs_cookie, webparser_budget, browser_budget
        has_wp = _has_engine(group, "webparser")
        has_br = _has_engine(group, "douyin-browser")
        remain = deadline - time.monotonic()
        extra_ok = (cn and has_wp and webparser_budget < WEBPARSER_MIN_S) or (
            has_br and browser_budget < BROWSER_MIN_S
        )
        if remain <= 0 and not extra_ok:
            return None
        if has_wp and remain < WEBPARSER_MIN_S:
            deadline = time.monotonic() + WEBPARSER_MIN_S + 1.0
            remain = deadline - time.monotonic()
            log.info("parse host=%s extend deadline so webparser gets %.0fs", host, WEBPARSER_MIN_S)
        if has_br and remain < BROWSER_MIN_S:
            deadline = time.monotonic() + BROWSER_MIN_S + 1.0
            remain = deadline - time.monotonic()
            log.info("parse host=%s extend deadline so douyin-browser gets %.0fs", host, BROWSER_MIN_S)
        if has_wp:
            webparser_budget = max(webparser_budget, min(per, max(remain, 0.0)))
        if has_br:
            browser_budget = max(browser_budget, min(per, max(remain, 0.0)))
        winner, stage_errors, stage_cookie = await _race(group, target, deadline, per)
        errors.extend(stage_errors)
        needs_cookie = needs_cookie or stage_cookie
        return winner

    for group, target, per, label in stages:
        winner = await run_group(group, target, per)
        if winner:
            log.info("parse host=%s engine=%s stage=%s ok", host, winner.get("extractor"), label)
            return winner

    # Douyin: never return needs_cookie until douyin-browser has finished or timed out.
    if douyin and browser and browser_budget < BROWSER_MIN_S:
        br = next((e for e in browser if getattr(e, "name", "") == "douyin-browser"), None)
        if br:
            deadline = time.monotonic() + BROWSER_MIN_S + 1.0
            log.info("parse host=%s last-chance douyin-browser 25s", host)
            winner = await run_group([br], resolved, BROWSER_MIN_S + 1.0)
            if winner:
                log.info("parse host=%s engine=%s stage=last-chance-browser ok", host, winner.get("extractor"))
                return winner

    # Do not return needs_cookie for CN platforms unless webparser was given a full 15s.
    if cn and webparser_budget < WEBPARSER_MIN_S:
        wp = next((e for e in fallback if getattr(e, "name", "") == "webparser"), None)
        if wp:
            deadline = time.monotonic() + WEBPARSER_MIN_S + 1.0
            log.info("parse host=%s last-chance webparser 15s", host)
            winner = await run_group([wp], resolved, WEBPARSER_MIN_S + 1.0)
            webparser_budget = max(webparser_budget, WEBPARSER_MIN_S)
            if winner:
                log.info("parse host=%s engine=%s stage=last-chance ok", host, winner.get("extractor"))
                return winner

    has_wp_engine = _has_engine(fallback, "webparser")
    wp_ready = (not cn) or (not has_wp_engine) or (webparser_budget >= WEBPARSER_MIN_S)
    br_ready = (not douyin) or (not browser) or (browser_budget >= BROWSER_MIN_S)

    if needs_cookie and wp_ready and br_ready:
        log.info(
            "parse host=%s needs_cookie cn=%s webparser_budget=%.1fs browser_budget=%.1fs",
            host,
            cn,
            webparser_budget,
            browser_budget,
        )
        detail = "；".join(errors[:8]) if errors else "needs cookie"
        hint = (
            "分享页/公共解析仍失败。可选最后手段：本机 Netscape cookies.txt "
            "或 VIDEO_EXTRACTOR_COOKIES / VIDEO_EXTRACTOR_COOKIES_FROM_BROWSER。"
            "不要把 Cookie 发给任何人。"
            if cn
            else "目标平台限制了游客访问。可展开「本机 Cookies」上传 Netscape cookies.txt "
            "或使用 VIDEO_EXTRACTOR_COOKIES / VIDEO_EXTRACTOR_COOKIES_FROM_BROWSER。"
            "不要把 Cookie 发给任何人。"
        )
        return {
            "ok": False,
            "error": detail[:400],
            "needs_cookie": True,
            "platform": platform_of(resolved),
            "hint": hint,
        }

    log.info("parse host=%s fail webparser_budget=%.1fs", host, webparser_budget)
    detail = "；".join(errors[:8]) if errors else "所有引擎均未能解析"
    return {"ok": False, "error": detail[:400], "platform": platform_of(resolved)}
