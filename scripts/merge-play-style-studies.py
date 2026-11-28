#!/usr/bin/env python3
"""Combine compatible, independently seeded initial and revision studies for mixed tables.

Usage: python scripts/merge-play-style-studies.py INITIAL_DIR REVISION_DIR OUTPUT_DIR
"""
import json
import sys
from pathlib import Path


def merge(initial, revision, initial_results, revision_results):
    for field in ("variants", "services", "contracts", "playerCounts", "factionSets", "seatRotationMode", "timingMode"):
        if initial[field] != revision[field]:
            raise ValueError(f"Studies differ in {field}")
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
