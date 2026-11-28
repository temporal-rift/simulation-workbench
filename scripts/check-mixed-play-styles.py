#!/usr/bin/env python3
"""Verify a mixed report and its exported case matrix against its manifest.

Usage: python scripts/check-mixed-play-styles.py MANIFEST.json REPORT.json CASES.jsonl
"""
import collections
import itertools
import json
import math
import sys
from pathlib import Path


def check(manifest, report, cases):
    if not report["complete"]:
        raise ValueError("Report is incomplete")
    players, = manifest["playerCounts"]
    tables = {tuple(seat["policyDigest"] for seat in table["seats"]): table for table in manifest["tables"]}
    if len(tables) != len(manifest["tables"]):
        raise ValueError("Duplicate table seating")
    for table in manifest["tables"]:
        for seat in table["seats"]:
            found = [c for c in report["cohorts"] if c["tableId"] == table["id"]
                     and c["playerCount"] == players and c["seatIndex"] == seat["seatIndex"]
                     and c["factionSet"] is None]
            if len(found) != 1 or found[0].get("policyDigest") != seat["policyDigest"]:
                raise ValueError(f"Report digest mismatch at {table['id']} seat {seat['seatIndex']}")
    expected = set()
    for seed, group, (digests, _) in itertools.product(manifest["seeds"], manifest["factionSets"], tables.items()):
        group = sorted(group)
        for rotation in range(players):
            order = group[rotation:] + group[:rotation]
            expected.add((str(seed), tuple(order), digests))
    seen, exposure = set(), collections.Counter()
    for case in cases:
        seats = sorted(case["seats"], key=lambda seat: seat["seatIndex"])
        if case["state"] != "SUCCEEDED" or case["playerCount"] != players:
            raise ValueError("A case did not succeed or has the wrong player count")
        if [s["seatIndex"] for s in seats] != list(range(players)):
            raise ValueError("Invalid seat indices")
        key = (str(case["seed"]), tuple(s["faction"] for s in seats), tuple(s["policyDigest"] for s in seats))
        if key in seen:
            raise ValueError("Duplicate case coordinate")
        seen.add(key)
        for seat in seats:
            exposure[(seat["policyDigest"], seat["faction"], seat["seatIndex"])] += 1
    if seen != expected:
        raise ValueError(f"Case matrix mismatch: {len(expected - seen)} missing, {len(seen - expected)} unexpected")
    factions = set(itertools.chain.from_iterable(manifest["factionSets"]))
    count = len(manifest["seeds"]) * math.comb(len(factions) - 1, players - 1)
    for policy, faction, seat in itertools.product(manifest["policies"], factions, range(players)):
        if exposure[(policy["artifactDigest"], faction, seat)] != count:
            raise ValueError("Unequal style, faction and seat exposure")
    return {"cases": len(seen), "exposurePerStyleFactionSeat": count}


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    report = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    with Path(sys.argv[3]).open(encoding="utf-8") as source:
        cases = [json.loads(line) for line in source if line.strip()]
    print(json.dumps(check(manifest, report, cases), sort_keys=True))
