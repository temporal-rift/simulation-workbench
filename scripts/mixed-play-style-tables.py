#!/usr/bin/env python3
"""Create balanced mixed-table manifests from a completed study.

Usage: python scripts/mixed-play-style-tables.py STUDY.json RESULTS.json OUTPUT_DIR
Uniform seat rotation requires a passing pooled cell for every seated faction.
"""

import copy
import sys
from pathlib import Path

from play_style_evidence import faction_coverage, read_json, write_json


def _homogeneous_digest(name, table):
    digests = {seat["policyDigest"] for seat in table["seats"]}
    if len(digests) != 1:
        raise ValueError(f"Study table {name} is not homogeneous")
    (digest,) = digests
    return digest


def _seat_entry(seat, policy):
    return {"seatIndex": seat, "policyId": policy["id"],
            "policyVersion": policy["version"], "policyDigest": policy["artifactDigest"]}


def _mixed_manifest(study, chosen, players):
    manifest = copy.deepcopy(study)
    manifest["name"] = f"Mixed fixed styles: {players} players"
    manifest["playerCounts"] = [players]
    manifest["factionSets"] = [group for group in study["factionSets"] if len(group) == players]
    manifest["seeds"] = [str(seed) for seed in range(42500, 42505)]
    manifest["policies"] = chosen
    manifest["tables"] = []
    for shift in range(len(chosen)):
        seats = [_seat_entry(seat, chosen[(seat + shift) % len(chosen)]) for seat in range(players)]
        manifest["tables"].append({"id": f"mixed-{shift + 1}", "seats": seats})
    return manifest


def manifests(study, results):
    factions = {faction for group in study["factionSets"] for faction in group}
    passing = [name for name, result in sorted(results["styles"].items())
               if faction_coverage(result["cells"]) == factions]
    if not 3 <= len(passing) <= 5:
        raise ValueError(f"Need 3 to 5 validated styles; found {len(passing)}")
    tables = {table["id"]: table for table in study["tables"]}
    policies = {policy["artifactDigest"]: policy for policy in study["policies"]}
    chosen = [policies[_homogeneous_digest(name, tables[name])] for name in passing]
    return {players: _mixed_manifest(study, chosen, players) for players in (3, 4, 5)}


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    study = read_json(sys.argv[1])
    results = read_json(sys.argv[2])
    output = Path(sys.argv[3])
    output.mkdir(parents=True, exist_ok=True)
    for players, manifest in manifests(study, results).items():
        write_json(output / f"mixed-{players}.json", manifest)
