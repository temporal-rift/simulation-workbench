#!/usr/bin/env python3
"""Combine compatible, independently seeded initial and revision studies for mixed tables.

Usage: python scripts/merge-play-style-studies.py INITIAL_DIR REVISION_DIR OUTPUT_DIR
"""
import json
import sys
from pathlib import Path

from play_style_evidence import manifest_sha256


def baseline(study):
    tables = [table for table in study["tables"] if table["id"] == "spread"]
    if len(tables) != 1 or not tables[0]["seats"]:
        raise ValueError("Expected one populated spread baseline table")
    return tuple((s["policyId"], s["policyVersion"], s["policyDigest"]) for s in tables[0]["seats"])


def merge(initial, revision, initial_results, revision_results):
    for label, manifest, results in (("Initial", initial, initial_results), ("Revision", revision, revision_results)):
        if results.get("manifestSha256") != manifest_sha256(manifest):
            raise ValueError(f"{label} measurements do not match the study manifest")
    for field in ("variants", "services", "contracts", "playerCounts", "factionSets", "seatRotationMode", "timingMode"):
        if initial[field] != revision[field]:
            raise ValueError(f"Studies differ in {field}")
    if baseline(initial) != baseline(revision):
        raise ValueError("Studies use different spread baselines")
    if set(initial["seeds"]) & set(revision["seeds"]):
        raise ValueError("Revision seeds overlap the initial study")
    for field in ("analysisSeed", "resamples", "minimumEffect"):
        if initial_results.get(field) != revision_results.get(field):
            raise ValueError(f"Study analyses differ in {field}")
    if set(initial_results["styles"]) & set(revision_results["styles"]):
        raise ValueError("Studies have duplicate style names")
    combined = dict(initial)
    existing = {p["artifactDigest"] for p in initial["policies"]}
    combined["policies"] = initial["policies"] + [p for p in revision["policies"] if p["artifactDigest"] not in existing]
    combined["tables"] = initial["tables"] + [t for t in revision["tables"] if t["id"] != "spread"]
    return combined, {"styles": {**initial_results["styles"], **revision_results["styles"]}}


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    first, second, out = map(Path, sys.argv[1:])
    data = [json.loads(path.read_text(encoding="utf-8")) for path in
            (first / "manifest.json", second / "manifest.json", first / "results.json", second / "results.json")]
    manifest, results = merge(*data)
    out.mkdir(parents=True, exist_ok=True)
    for name, value in (("combined-manifest.json", manifest), ("combined-results.json", results)):
        (out / name).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8", newline="\n")
