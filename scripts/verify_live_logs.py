"""Check browser-compatible SSE, a live reconnect, and the full log download."""

import argparse
import re
import time
from pathlib import Path
from urllib.request import Request, urlopen

from verify_pipeline import read_json, request, submit


PROGRESS = re.compile(r"BuildHive fixture progress (\d+)")


def events(url, deadline, cursor=None):
    headers = {"Accept": "text/event-stream"}
    if cursor:
        headers["Last-Event-ID"] = cursor
    with urlopen(Request(url, headers=headers), timeout=25) as response:
        assert response.headers.get_content_type() == "text/event-stream", response.headers
        lines = []
        event_id = None
        kind = "message"
        for raw in response:
            if time.monotonic() >= deadline:
                raise TimeoutError("Log stream did not finish before the deadline")
            line = raw.decode("utf-8").rstrip("\r\n")
            if not line:
                if lines:
                    yield kind, event_id, "\n".join(lines)
                lines, event_id, kind = [], None, "message"
            elif line.startswith("data: "):
                lines.append(line[6:])
            elif line.startswith("id: "):
                event_id = line[4:]
            elif line.startswith("event: "):
                kind = line[7:]


def verify(base, archive, timeout):
    pipeline = submit(base, archive)
    jobs_url = f"/api/pipelines/{pipeline}/jobs"

    def unit_tests():
        return next(job for job in read_json(base, jobs_url) if job["jobType"] == "UNIT_TESTS")

    deadline = time.monotonic() + timeout
    job = unit_tests()
    while job["state"] in {"BLOCKED", "READY", "CLAIMED"} and time.monotonic() < deadline:
        time.sleep(1)
        job = unit_tests()
    assert job["state"] == "RUNNING", job

    path = f"/api/jobs/{job['id']}/logs"
    stream_url = f"{base}{path}/stream?attempt=1"
    prefix = []
    first = events(stream_url, deadline)
    try:
        for kind, cursor, content in first:
            assert kind == "message" and cursor, (kind, cursor, content)
            prefix.append(content)
            if any(PROGRESS.fullmatch(line) for line in content.splitlines()):
                assert unit_tests()["state"] == "RUNNING"
                print("Received progress while Unit Tests was running.", flush=True)
                break
        else:
            raise AssertionError("The stream ended before live progress arrived")
    finally:
        first.close()

    download_path = f"{path}/download?attempt=1"
    with urlopen(Request(base + download_path, method="HEAD"), timeout=10) as response:
        assert response.status == 200
        assert "attempt-1.log" in response.headers.get("Content-Disposition", "")
    print("Log download is available while the stream is disconnected.", flush=True)
    suffix = []
    for kind, _, content in events(stream_url, deadline, cursor):
        if kind == "complete":
            break
        suffix.append(content)
    else:
        raise AssertionError("Reconnected stream did not send a completion event")
    full_log = request(base, download_path).decode("utf-8")
    assert "".join(prefix + suffix) == full_log, "Stream replay differs from the complete attempt log"
    markers = [int(match[1]) for line in full_log.splitlines() if (match := PROGRESS.fullmatch(line))]
    assert markers == list(range(1, 13)), markers
    print("All 12 progress markers and all log bytes matched after reconnecting.", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--base-url", default="http://localhost:3000")
    parser.add_argument("--timeout", default=240, type=int)
    args = parser.parse_args()
    verify(args.base_url.rstrip("/"), args.archive, args.timeout)
