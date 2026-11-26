"""Verify the generated sample cases against a running BuildHive cluster."""

import argparse
import json
import re
from pathlib import Path
from urllib.error import HTTPError
from xml.etree import ElementTree

from verify_pipeline import read_json, request, submit, verify


DIST = Path(__file__).resolve().parents[1] / "sample-projects" / "dist"


def verify_rejection(base_url, archive, expected_message):
    try:
        submit(base_url, archive)
    except HTTPError as error:
        payload = json.load(error)
        if error.code == 400 and expected_message in payload.get("message", ""):
            print(f"Verified {archive.name}: HTTP 400, {payload['message']}", flush=True)
            return
        raise AssertionError(f"Unexpected rejection for {archive.name}: HTTP {error.code}, {payload}") from error
    raise AssertionError(f"{archive.name} unexpectedly created a pipeline")


def verify_case(base_url, name, case, timeout):
    archive = DIST / f"{name}.zip"
    if case["status"] == "REJECTED":
        verify_rejection(base_url, archive, case["message"])
        return

    pipeline_id = verify(base_url, archive, case["status"], timeout, case.get("stage"))
    if "quality" in case:
        verify_quality(base_url, pipeline_id, case["quality"])
    if name == "no-tests":
        artifacts = read_json(base_url, f"/api/pipelines/{pipeline_id}/artifacts")
        if any(artifact["artifactType"] == "TEST_REPORT" for artifact in artifacts):
            raise AssertionError(f"{name}: unexpected test report")
    if name in {"slow-tests", "verbose-tests"}:
        verify_test_log(base_url, pipeline_id, name)
    if "stage" in case:
        jobs = read_json(base_url, f"/api/pipelines/{pipeline_id}/jobs")
        failed = next(job for job in jobs if job["jobType"] == case["stage"])
        actual = (failed["failureClassification"], failed["retryCount"])
        expected = (case["classification"], case["attempts"])
        if actual != expected:
            raise AssertionError(f"{name}: expected {expected}, got {actual}")
        print(f"Verified {name}: {case['stage']}, {actual[0]}, {actual[1]} attempt(s)", flush=True)


def verify_test_log(base_url, pipeline_id, name):
    jobs = read_json(base_url, f"/api/pipelines/{pipeline_id}/jobs")
    job = next(job for job in jobs if job["jobType"] == "UNIT_TESTS")
    log = request(base_url, f"/api/jobs/{job['id']}/logs/download?attempt=1").decode("utf-8")
    marker, count = ("progress", 12) if name == "slow-tests" else ("output", 3_200)
    actual = [int(match) for match in re.findall(rf"^BuildHive fixture {marker} (\d+)$", log, re.MULTILINE)]
    if actual != list(range(1, count + 1)):
        raise AssertionError(f"{name}: expected {count} numbered lines in order, got {len(actual)}")
    print(f"Verified {name}: all {count} numbered lines in the full log download", flush=True)


def verify_quality(base_url, pipeline_id, expected):
    artifacts = read_json(base_url, f"/api/pipelines/{pipeline_id}/artifacts")
    report = next(a for a in artifacts if a["artifactType"] == "BUILD_REPORT_JSON")
    summary = read_json(base_url, report["downloadUrl"])
    stage = next(job for job in summary["stages"] if job["type"] == "STATIC_ANALYSIS")
    results = [a for a in artifacts if a["artifactType"] == "CHECKSTYLE_REPORT" and a["jobId"] == stage["jobId"]]
    if expected == "TOOL_ERROR":
        assert not results and stage["state"] == "FAILED", stage
        reason = stage["attempts"][-1]["failureReason"]
        assert reason.startswith(("Checkstyle did not complete", "Checkstyle produced invalid report XML")), stage
    else:
        assert results and stage["analyzer"] == "Checkstyle", stage
        assert {a["downloadUrl"] for a in results} <= {output["url"] for output in summary["outputs"]}
        violations = 0
        for result in results:
            xml = ElementTree.fromstring(request(base_url, result["downloadUrl"]))
            assert xml.tag == "checkstyle", xml.tag
            violations += len(xml.findall(".//error[@severity='error']"))
        assert (violations == 0) == (expected == "PASSED"), (expected, violations)
        assert stage["state"] == ("SUCCEEDED" if expected == "PASSED" else "FAILED"), stage
    print(f"Verified Checkstyle outcome: {expected}", flush=True)


if __name__ == "__main__":
    if not (DIST / "cases.json").is_file():
        raise SystemExit("Run python sample-projects/generate_samples.py first.")
    cases = json.loads((DIST / "cases.json").read_text(encoding="utf-8"))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cases", nargs="*", metavar="CASE", help="Case names without .zip. Defaults to all.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--timeout", type=int, default=600, help="Timeout in seconds for each pipeline.")
    args = parser.parse_args()
    names = args.cases or list(cases)
    unknown = [name for name in names if name not in cases]
    if unknown:
        parser.error(f"Unknown cases: {', '.join(unknown)}. Available: {', '.join(cases)}")
    for name in names:
        verify_case(args.base_url.rstrip("/"), name, cases[name], args.timeout)
    print(f"Passed {len(names)} sample case(s).", flush=True)
