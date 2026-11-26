"""Verify cancellation stops real Maven processes in a running review Compose stack."""
import argparse
import subprocess
import time
from pathlib import Path
from verify_pipeline import read_json, request, submit


def processes(containers, pipeline_id):
    found = []
    for container in containers:
        output = subprocess.check_output(
            ["docker", "top", container, "-eo", "pid,args"], text=True)
        found.extend(line for line in output.splitlines() if pipeline_id in line)
    return found


def verify(base_url, archive, containers, timeout):
    pipeline_id = submit(base_url, archive)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        jobs = read_json(base_url, f"/api/pipelines/{pipeline_id}/jobs")
        if any(j["jobType"] == "UNIT_TESTS" and j["state"] == "RUNNING" for j in jobs):
            running = processes(containers, pipeline_id)
            if running:
                break
        time.sleep(1)
    else:
        raise AssertionError("No running unit-test process observed before deadline")
    print(f"Observed {len(running)} build processes before cancellation", flush=True)
    request(base_url, f"/api/pipelines/{pipeline_id}/cancel", b"")
    deadline = time.monotonic() + 15
    while processes(containers, pipeline_id):
        if time.monotonic() >= deadline:
            raise AssertionError("Build processes survived cancellation")
        time.sleep(0.5)
    pipeline = read_json(base_url, f"/api/pipelines/{pipeline_id}")
    jobs = read_json(base_url, f"/api/pipelines/{pipeline_id}/jobs")
    artifacts = read_json(base_url, f"/api/pipelines/{pipeline_id}/artifacts")
    assert pipeline["status"] == "CANCELLED", pipeline
    assert len(jobs) == 7 and all(j["state"] in {"SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED"} for j in jobs), jobs
    assert not any(a["artifactType"] == "JAR" for a in artifacts), artifacts
    print(f"Verified {pipeline_id}: CANCELLED, all jobs terminal, no build processes or JAR", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--base-url", default="http://localhost:18080")
    parser.add_argument("--containers", nargs="+", required=True)
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    verify(args.base_url.rstrip("/"), args.archive, args.containers, args.timeout)
