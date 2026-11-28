#!/usr/bin/env python3
"""Compare exported succeeded-case facts, paired by full case coordinates and seed.

Usage: python scripts/analyze-play-styles.py MANIFEST.json EXPERIMENT.json REPORT.json CASES.jsonl FACTS.jsonl RESULT.json
Case lines contain runId, caseId, seed, playerCount, state and seats. Fact lines contain caseId and
seats in the workbench's stored case-facts format. The manifest supplies the digest-to-table mapping.
"""

import json
import random
import sys
from collections import defaultdict
from pathlib import Path

from play_style_evidence import check_run, manifest_sha256

FACTIONS = ("ERASERS", "PROPHETS", "REVISIONISTS", "WEAVERS", "ACTIVISTS")
SPECIAL = dict(zip(FACTIONS, ("ANNIHILATE", "FORESIGHT", "REWRITE", "THREAD", "EXPOSE")))
SPECIAL_REVISION = dict(zip(FACTIONS, ("CORRUPT", "SEAL", "OBSCURE", "REWEAVE", "EXPOSE")))
CARD_MEASURES = {"cautious": {"NULLIFY"}, "contrarian": {"SUPPRESS"},
                 "information-oriented": {"SCAN", "INTERCEPT", "TRACE", "DECOY"}}
PER_SEED = {3: 30, 4: 20, 5: 5}


def measure(seat, style):
    if style == "special-heavy":
        return seat["specialAccepts"].get(SPECIAL[seat["faction"]], 0)
    if style == "special-heavy-revision":
        return seat["specialAccepts"].get(SPECIAL_REVISION[seat["faction"]], 0)
    return sum(value for key, value in seat["cardsPlayed"].items() if key.split("/", 1)[0] in CARD_MEASURES[style])


def percentile(values, probability):
    values = sorted(values)
    index = probability * (len(values) - 1)
    lo = int(index)
    return values[lo] + (values[min(lo + 1, len(values) - 1)] - values[lo]) * (index - lo)


def read_lines(path):
    with Path(path).open(encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def analyze(manifest, cases, facts):
    table_by_digest = {table["seats"][0]["policyDigest"]: table["id"] for table in manifest["tables"]}
    table_names = set(table_by_digest.values())
    if ("spread" not in table_names or len(table_names) != len(manifest["tables"]
            ) or not table_names <= {"spread", *CARD_MEASURES, "special-heavy", "special-heavy-revision"}):
        raise ValueError("Unexpected or duplicate study table digest")
    fact_by_id = {}
    for fact in facts:
        if fact["caseId"] in fact_by_id:
            raise ValueError(f"Duplicate facts for {fact['caseId']}")
        fact_by_id[fact["caseId"]] = fact
    indexed = {}
    for case in cases:
        seats = sorted(case["seats"], key=lambda seat: seat["seatIndex"])
        digests = {seat["policyDigest"] for seat in seats}
        if len(digests) != 1 or next(iter(digests)) not in table_by_digest:
            raise ValueError("Each study case must identify one homogeneous table")
        table = table_by_digest[next(iter(digests))]
        coordinate = (str(case["seed"]), case["playerCount"], tuple((s["seatIndex"], s["faction"]) for s in seats))
        key = (table, coordinate)
        if key in indexed:
            raise ValueError(f"Duplicate case coordinate: {key}")
        indexed[key] = case
    expected_seeds = set(manifest["seeds"])
    if any(coordinate[0] not in expected_seeds for _, coordinate in indexed):
        raise ValueError("Unexpected seed")
    result = {"manifestSha256": manifest_sha256(manifest), "analysisSeed": 1, "resamples": 2000,
              "minimumEffect": 0.1, "styles": {}}
    baseline = {coord: case for (table, coord), case in indexed.items() if table == "spread"}
    for style in sorted(set(table_by_digest.values()) - {"spread"}):
        candidate = {coord: case for (table, coord), case in indexed.items() if table == style}
        blocks = defaultdict(list)
        exclusions = []
        for seed in sorted(expected_seeds, key=int):
            left = {coord: case for coord, case in baseline.items() if coord[0] == seed}
            right = {coord: case for coord, case in candidate.items() if coord[0] == seed}
            expected = set()
            for faction_set in manifest["factionSets"]:
                faction_set = sorted(faction_set)
                for rotation in range(len(faction_set)):
                    ordered = faction_set[rotation:] + faction_set[:rotation]
                    expected.add((seed, len(ordered), tuple(enumerate(ordered))))
            complete = left.keys() == right.keys() == expected and all(
                sum(coord[1] == n for coord in left) == count for n, count in PER_SEED.items())
            if complete:
                complete = all(case["state"] == "SUCCEEDED" and case["caseId"] in fact_by_id
                               for case in (*left.values(), *right.values()))
            if not complete:
                exclusions.append(seed)
                continue
            for coord in sorted(left):
                before = {s["seat"]: s for s in fact_by_id[left[coord]["caseId"]]["seats"]}
                after = {s["seat"]: s for s in fact_by_id[right[coord]["caseId"]]["seats"]}
                if set(before) != set(after) or set(before) != {seat for seat, _ in coord[2]}:
                    raise ValueError(f"Fact seat mismatch for {coord}")
                for seat, faction in coord[2]:
                    if before[seat]["faction"] != faction or after[seat]["faction"] != faction:
                        raise ValueError(f"Fact faction mismatch for {coord}")
                    blocks[seed].append((coord[1], faction, measure(before[seat], style), measure(after[seat], style)))
        cells = []
        for faction in FACTIONS:
            for count in (None, 3, 4, 5):
                sums = []
                for seed in sorted(blocks, key=int):
                    rows = [row for row in blocks[seed] if row[1] == faction and (count is None or row[0] == count)]
                    sums.append((sum(row[2] for row in rows), sum(row[3] for row in rows), len(rows)))
                total = sum(row[2] for row in sums)
                if len(sums) < 2 or not total:
                    cells.append({"faction": faction, "playerCount": count, "status": "INSUFFICIENT_SAMPLE"})
                    continue
                before, after = sum(s[0] for s in sums), sum(s[1] for s in sums)
                randomizer = random.Random(1)
                samples = []
                for _ in range(2000):
                    drawn = randomizer.choices(sums, k=len(sums))
                    samples.append(sum(b - a for a, b, _ in drawn) / sum(n for _, _, n in drawn))
                low, high = percentile(samples, .025), percentile(samples, .975)
                cells.append({"faction": faction, "playerCount": count, "blocks": len(sums),
                              "seatGamesPerSide": total, "baselineCount": before, "candidateCount": after,
                              "effect": (after - before) / total, "interval": [low, high],
                              "passes": len(sums) >= 80 and low > 0.1})
        result["styles"][style] = {"excludedSeeds": exclusions, "cells": cells}
    return result


if __name__ == "__main__":
    if len(sys.argv) != 7:
        raise SystemExit(__doc__)
    manifest, experiment, report = (json.loads(Path(path).read_text(encoding="utf-8")) for path in sys.argv[1:4])
    cases = read_lines(sys.argv[4])
    check_run(manifest, experiment, report, cases)
    result = analyze(manifest, cases, read_lines(sys.argv[5]))
    Path(sys.argv[6]).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8", newline="\n")
