"""Exercise the public submission API and verify the resulting pipeline and JAR."""
import argparse
import hashlib
import http.client
import io
import json
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path


def request(base_url, path, data=None, headers=None):
    req = urllib.request.Request(base_url + path, data=data, headers=headers or {})
    with urllib.request.urlopen(req, timeout=30) as response:
        return response.read()


def read_json(base_url, path):
    return json.loads(request(base_url, path))


def submit(base_url, archive):
    boundary = uuid.uuid4().hex
    payload = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{archive.name}"\r\n'
        "Content-Type: application/zip\r\n\r\n"
    ).encode() + archive.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    submission = json.loads(request(base_url, "/api/submissions", payload,
                                    {"Content-Type": f"multipart/form-data; boundary={boundary}"}))
    pipeline_id = str(uuid.UUID(submission["pipelineId"]))
    print(f"Submitted {archive.name}: {pipeline_id}", flush=True)
    return pipeline_id


def verify(base_url, archive, expected, timeout, failed_stage=None):
    deadline = time.monotonic() + timeout
    while True:
        try:
            workers = read_json(base_url, "/api/workers")
            active = {worker["workerId"] for worker in workers if worker["status"] == "ACTIVE"}
            if len(active) >= 2:
                break
        except (OSError, http.client.HTTPException):
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError("Two distinct active workers did not become ready")
        time.sleep(2)

    pipeline_id = submit(base_url, archive)
    while True:
        pipeline = read_json(base_url, f"/api/pipelines/{pipeline_id}")
        if pipeline["status"] in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Pipeline {pipeline_id} timed out: {pipeline}")
        time.sleep(2)
    if pipeline["status"] != expected:
        raise AssertionError(f"Expected {expected}, got {pipeline}")
    jobs = read_json(base_url, f"/api/pipelines/{pipeline_id}/jobs")
    if len(jobs) != 7 or any(job["state"] not in {"SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED"} for job in jobs):
        raise AssertionError(f"Pipeline has unfinished or missing jobs: {jobs}")
    artifacts = read_json(base_url, f"/api/pipelines/{pipeline_id}/artifacts")
    jars = [artifact for artifact in artifacts if artifact["artifactType"] == "JAR"]
    if expected == "FAILED":
        if jars or not any(job["state"] == "FAILED" for job in jobs):
            raise AssertionError(f"Failed pipeline has unexpected results: {jobs}, {artifacts}")
        if failed_stage and not any(job["jobType"] == failed_stage and job["state"] == "FAILED" for job in jobs):
            raise AssertionError(f"Expected failure in {failed_stage}: {jobs}")
    else:
        if not jars:
            raise AssertionError("Successful pipeline did not publish a JAR")
        for artifact in jars:
            content = request(base_url, f'/api/artifacts/{artifact["id"]}/download')
            if not content or len(content) != artifact["fileSizeBytes"]:
                raise AssertionError("JAR size does not match metadata")
            digest = hashlib.sha256(content).hexdigest()
            if digest != artifact["sha256"]:
                raise AssertionError("JAR checksum does not match metadata")
            with zipfile.ZipFile(io.BytesIO(content)) as jar:
                if jar.testzip() is not None or not any(name.endswith(".class") for name in jar.namelist()):
                    raise AssertionError("JAR is invalid or has no compiled classes")
            print(f'Validated {artifact["fileName"]}: {len(content)} bytes, SHA-256 {digest}', flush=True)
    print(f"Verified {pipeline_id}: {expected}, {len(jobs)} terminal jobs", flush=True)
    return pipeline_id


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--expected", choices=["SUCCEEDED", "FAILED"], default="SUCCEEDED")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--failed-stage", choices=[
        "PROJECT_VALIDATION", "DEPENDENCY_RESOLUTION", "COMPILATION", "UNIT_TESTS",
        "STATIC_ANALYSIS", "PACKAGING", "REPORT_GENERATION",
    ])
    args = parser.parse_args()
    verify(args.base_url.rstrip("/"), args.archive, args.expected, args.timeout, args.failed_stage)
