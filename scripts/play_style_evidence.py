"""Identity checks shared by the fixed play-style study scripts."""

import hashlib
import json


def manifest_sha256(manifest):
    """Fingerprint the exact study inputs independently of JSON formatting."""
    encoded = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def check_run(manifest, experiment, report, cases):
    digest = experiment.get("manifestDigest")
    if experiment.get("manifest") != manifest or not digest or report.get("manifestDigest") != digest:
        raise ValueError("Report manifest digest mismatch")
    run_id = report.get("runId")
    if not run_id or any(case.get("runId") != run_id for case in cases):
        raise ValueError("Report and case run IDs do not match")
