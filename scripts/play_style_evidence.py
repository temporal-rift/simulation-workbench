"""Identity checks shared by the fixed play-style study scripts."""

import hashlib
import json
from pathlib import Path


def manifest_sha256(manifest):
    """Fingerprint the exact study inputs independently of JSON formatting."""
    encoded = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def file_sha256(path):
    """Return the hex SHA-256 digest of a file's bytes."""
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def read_json(path):
    """Read a JSON document."""
    return json.loads(Path(path).read_text(encoding="utf-8"))


def read_jsonl(path):
    """Read a JSON-lines file, skipping blank lines."""
    with Path(path).open(encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def write_json(path, data):
    """Write JSON with portable LF line endings."""
    Path(path).write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")


def source_identity(report, report_path, cases):
    # Run identity is recorded separately, including for archived case rows exported before runId was added.
    rows = [{key: value for key, value in case.items() if key != "runId"} for case in cases]
    return {"runId": report["runId"], "manifestDigest": report["manifestDigest"],
            "reportSha256": file_sha256(report_path), "caseRowsSha256": manifest_sha256(rows)}


def check_run(manifest, experiment, report, cases):
    if report.get("complete") is not True:
        raise ValueError("Report is incomplete")
    digest = experiment.get("manifestDigest")
    if experiment.get("manifest") != manifest or not digest or report.get("manifestDigest") != digest:
        raise ValueError("Report manifest digest mismatch")
    run_id = report.get("runId")
    if not run_id or any(case.get("runId") != run_id for case in cases):
        raise ValueError("Report and case run IDs do not match")


def faction_coverage(cells):
    passed = set()
    for cell in cells:
        if cell["playerCount"] is not None:
            continue
        expected = cell.get("blocks", 0) >= 80 and cell.get("interval", [float("-inf")])[0] > 0.1
        if cell.get("passes", False) != expected:
            raise ValueError(f"Inconsistent pooled verdict for {cell['faction']}")
        if expected:
            passed.add(cell["faction"])
    return passed
