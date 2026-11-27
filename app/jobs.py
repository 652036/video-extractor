"""In-memory parse jobs. Source page URL is kept only in process memory."""

from __future__ import annotations

import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .config import COOKIE_UPLOAD_DIR, DOWNLOAD_DIR

JOB_TTL_SECONDS = 3600.0
MAX_JOBS = 200


@dataclass
class Job:
    id: str
    page_url: str
    title: str
    ext: str
    filesize: int | None
    thumbnail: str | None
    extractor: str
    platform: str
    direct_url: str | None
    needs_merge: bool = False
    filepath: Path | None = None
    created_at: float = field(default_factory=time.time)
    extra: dict[str, Any] = field(default_factory=dict)
    lock: threading.Lock = field(default_factory=threading.Lock, repr=False, compare=False)


def _cleanup_job_files(job: Job) -> None:
    if job.filepath:
        try:
            Path(job.filepath).unlink(missing_ok=True)
        except OSError:
            pass
    uploaded = (job.extra or {}).get("uploaded_cookiefile")
    if uploaded:
        try:
            p = Path(uploaded).resolve()
            if COOKIE_UPLOAD_DIR.resolve() in p.parents:
                p.unlink(missing_ok=True)
        except OSError:
            pass


class JobStore:
    def __init__(self, ttl: float = JOB_TTL_SECONDS, max_jobs: int = MAX_JOBS) -> None:
        self._lock = threading.Lock()
        self._jobs: dict[str, Job] = {}
        self._ttl = ttl
        self._max_jobs = max_jobs

    def _evict_locked(self, now: float) -> list[Job]:
        removed: list[Job] = []
        expired_ids = [jid for jid, job in self._jobs.items() if now - job.created_at > self._ttl]
        for jid in expired_ids:
            removed.append(self._jobs.pop(jid))
        if len(self._jobs) > self._max_jobs:
            overflow = len(self._jobs) - self._max_jobs
            oldest = sorted(self._jobs.values(), key=lambda j: j.created_at)[:overflow]
            for job in oldest:
                self._jobs.pop(job.id, None)
                removed.append(job)
        return removed

    def cleanup(self, now: float | None = None) -> int:
        ts = time.time() if now is None else now
        with self._lock:
            removed = self._evict_locked(ts)
        for job in removed:
            _cleanup_job_files(job)
        return len(removed)

    def create(self, **kwargs) -> Job:
        job_id = uuid.uuid4().hex
        job = Job(id=job_id, **kwargs)
        now = time.time()
        with self._lock:
            self._jobs[job_id] = job
            removed = self._evict_locked(now)
        for old in removed:
            _cleanup_job_files(old)
        return job

    def get(self, job_id: str) -> Job | None:
        now = time.time()
        with self._lock:
            job = self._jobs.get(job_id)
            if job is not None and now - job.created_at > self._ttl:
                self._jobs.pop(job_id, None)
                expired = job
                job = None
            else:
                expired = None
        if expired is not None:
            _cleanup_job_files(expired)
        return job

    def set_path(self, job_id: str, path: Path) -> None:
        with self._lock:
            job = self._jobs.get(job_id)
            if job:
                job.filepath = path


STORE = JobStore()
DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
