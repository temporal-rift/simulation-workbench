#!/usr/bin/env python3
"""Combine compatible, independently seeded initial and revision studies for mixed tables.

Usage: python scripts/merge-play-style-studies.py INITIAL_DIR REVISION_DIR OUTPUT_DIR
"""
import sys
from pathlib import Path

from play_style_evidence import manifest_sha256, read_json, write_json

STUDY_FIELDS = ("variants", "services", "contracts", "playerCounts", "factionSets", "seatRotationMode", "timingMode")
RESULT_FIELDS = ("analysisSeed", "resamples", "minimumEffect")


def baseline(study):
    matches = [table for table in study["tables"] if table["id"] == "spread"]
    if len(matches) != 1 or not matches[0]["seats"]:
        raise ValueError("Expected one populated spread baseline table")
    return tuple((seat["policyId"], seat["policyVersion"], seat["policyDigest"]) for seat in matches[0]["seats"])


def merge(initial, revision, initial_results, revision_results):
    for label, manifest, results in (("Initial", initial, initial_results), ("Revision", revision, revision_results)):
        if results.get("manifestSha256") != manifest_sha256(manifest):
            raise ValueError(f"{label} measurements do not match the study manifest")
    for field in STUDY_FIELDS:
        if initial[field] != revision[field]:
            raise ValueError(f"Studies differ in {field}")
    if baseline(initial) != baseline(revision):
        raise ValueError("Studies use different spread baselines")
    if set(initial["seeds"]) & set(revision["seeds"]):
        raise ValueError("Revision seeds overlap the initial study")
    for field in RESULT_FIELDS:
        if initial_results.get(field) != revision_results.get(field):
            raise ValueError(f"Study analyses differ in {field}")
    if set(initial_results["styles"]) & set(revision_results["styles"]):
        raise ValueError("Studies have duplicate style names")
    existing = {policy["artifactDigest"] for policy in initial["policies"]}
    combined = dict(initial)
    combined["policies"] = initial["policies"] + [policy for policy in revision["policies"]
                                                 if policy["artifactDigest"] not in existing]
    combined["tables"] = initial["tables"] + [table for table in revision["tables"] if table["id"] != "spread"]
    return combined, {"styles": {**initial_results["styles"], **revision_results["styles"]}}


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    first, second, out = map(Path, sys.argv[1:])
    data = [read_json(path) for path in
            (first / "manifest.json", second / "manifest.json", first / "results.json", second / "results.json")]
    manifest, results = merge(*data)
    out.mkdir(parents=True, exist_ok=True)
    for name, value in (("combined-manifest.json", manifest), ("combined-results.json", results)):
        write_json(out / name, value)
