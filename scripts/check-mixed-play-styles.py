#!/usr/bin/env python3
"""Verify a mixed report and its exported case matrix against its manifest.

Usage: python scripts/check-mixed-play-styles.py MANIFEST.json EXPERIMENT.json REPORT.json CASES.jsonl
"""
import json
import math
import sys
from collections import Counter
from itertools import chain, product

from play_style_evidence import check_run, read_json, read_jsonl


def _check_cohorts(manifest, report, players):
    for table in manifest["tables"]:
        for seat in table["seats"]:
            matches = [cohort for cohort in report["cohorts"] if cohort["tableId"] == table["id"]
                       and cohort["playerCount"] == players and cohort["seatIndex"] == seat["seatIndex"]
                       and cohort["factionSet"] is None]
            if len(matches) != 1 or matches[0].get("policyDigest") != seat["policyDigest"]:
                raise ValueError(f"Report digest mismatch at {table['id']} seat {seat['seatIndex']}")


def _expected_matrix(manifest, seatings, players):
    expected = set()
    for seed, group, digests in product(manifest["seeds"], manifest["factionSets"], seatings):
        ordered = sorted(group)
        for rotation in range(players):
            expected.add((str(seed), tuple(ordered[rotation:] + ordered[:rotation]), digests))
    return expected


def check(manifest, experiment, report, cases):
    check_run(manifest, experiment, report, cases)
    players, = manifest["playerCounts"]
    tables = {tuple(seat["policyDigest"] for seat in table["seats"]): table for table in manifest["tables"]}
    if len(tables) != len(manifest["tables"]):
        raise ValueError("Duplicate table seating")
    _check_cohorts(manifest, report, players)
    expected = _expected_matrix(manifest, list(tables), players)
    seen = set()
    exposure = Counter()
    for case in cases:
        seats = sorted(case["seats"], key=lambda seat: seat["seatIndex"])
        if case["state"] != "SUCCEEDED" or case["playerCount"] != players:
            raise ValueError("A case did not succeed or has the wrong player count")
        if [seat["seatIndex"] for seat in seats] != list(range(players)):
            raise ValueError("Invalid seat indices")
        key = (str(case["seed"]), tuple(seat["faction"] for seat in seats),
               tuple(seat["policyDigest"] for seat in seats))
        if key in seen:
            raise ValueError("Duplicate case coordinate")
        seen.add(key)
        for seat in seats:
            exposure[(seat["policyDigest"], seat["faction"], seat["seatIndex"])] += 1
    if seen != expected:
        raise ValueError(f"Case matrix mismatch: {len(expected - seen)} missing, {len(seen - expected)} unexpected")
    factions = set(chain.from_iterable(manifest["factionSets"]))
    count = len(manifest["seeds"]) * math.comb(len(factions) - 1, players - 1)
    for policy, faction, seat in product(manifest["policies"], factions, range(players)):
        if exposure[(policy["artifactDigest"], faction, seat)] != count:
            raise ValueError("Unequal style, faction and seat exposure")
    return {"cases": len(seen), "exposurePerStyleFactionSeat": count}


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    manifest = read_json(sys.argv[1])
    experiment = read_json(sys.argv[2])
    report = read_json(sys.argv[3])
    print(json.dumps(check(manifest, experiment, report, read_jsonl(sys.argv[4])), sort_keys=True))
