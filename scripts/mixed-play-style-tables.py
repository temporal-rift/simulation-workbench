#!/usr/bin/env python3
"""Create balanced mixed-table manifests from a completed study.

Usage: python scripts/mixed-play-style-tables.py STUDY.json RESULTS.json OUTPUT_DIR
Uniform seat rotation requires a passing pooled cell for every seated faction.
"""

import copy
import json
import sys
from pathlib import Path

from play_style_evidence import faction_coverage


def manifests(study, results):
    factions = {faction for group in study["factionSets"] for faction in group}
    passing = [name for name, result in sorted(results["styles"].items())
               if faction_coverage(result["cells"]) == factions]
    if not 3 <= len(passing) <= 5:
        raise ValueError(f"Need 3 to 5 validated styles; found {len(passing)}")
    tables = {table["id"]: table for table in study["tables"]}
    policies = {p["artifactDigest"]: p for p in study["policies"]}
    chosen = []
    for name in passing:
        digests = {seat["policyDigest"] for seat in tables[name]["seats"]}
        if len(digests) != 1:
            raise ValueError(f"Study table {name} is not homogeneous")
        chosen.append(policies[digests.pop()])
    result = {}
    for players in (3, 4, 5):
        manifest = copy.deepcopy(study)
        manifest["name"] = f"Mixed fixed styles: {players} players"
        manifest["playerCounts"] = [players]
        manifest["factionSets"] = [group for group in study["factionSets"] if len(group) == players]
        manifest["seeds"] = [str(seed) for seed in range(42500, 42505)]
        manifest["policies"] = chosen
        manifest["tables"] = []
        for shift in range(len(chosen)):
            seats = []
            for seat in range(players):
                policy = chosen[(seat + shift) % len(chosen)]
                seats.append({"seatIndex": seat, "policyId": policy["id"],
                              "policyVersion": policy["version"], "policyDigest": policy["artifactDigest"]})
            manifest["tables"].append({"id": f"mixed-{shift + 1}", "seats": seats})
        result[players] = manifest
    return result


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    study, results = (json.loads(Path(p).read_text(encoding="utf-8")) for p in sys.argv[1:3])
    output = Path(sys.argv[3])
    output.mkdir(parents=True, exist_ok=True)
    for players, manifest in manifests(study, results).items():
        (output / f"mixed-{players}.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
