#!/usr/bin/env python3
"""Compare exported succeeded-case facts, paired by full case coordinates and seed.

Usage: python scripts/analyze_play_styles.py MANIFEST.json EXPERIMENT.json REPORT.json CASES.jsonl FACTS.jsonl RESULT.json
Case lines contain runId, caseId, seed, playerCount, state and seats. Fact lines contain caseId and
seats in the workbench's stored case-facts format. The manifest supplies the digest-to-table mapping.
"""

import random
import sys
from collections import defaultdict, namedtuple

from play_style_evidence import (
    check_run,
    file_sha256,
    manifest_sha256,
    read_json,
    read_jsonl,
    source_identity,
    write_json,
)

FACTIONS = ("ERASERS", "PROPHETS", "REVISIONISTS", "WEAVERS", "ACTIVISTS")
SPECIAL = {faction: action for faction, action in
           zip(FACTIONS, ("ANNIHILATE", "FORESIGHT", "REWRITE", "THREAD", "EXPOSE"))}
SPECIAL_REVISION = {faction: action for faction, action in
                    zip(FACTIONS, ("CORRUPT", "SEAL", "OBSCURE", "REWEAVE", "EXPOSE"))}
CARD_MEASURES = {"cautious": {"NULLIFY"}, "contrarian": {"SUPPRESS"},
                 "information-oriented": {"SCAN", "INTERCEPT", "TRACE", "DECOY"}}
PER_SEED = {3: 30, 4: 20, 5: 5}

ANALYSIS_SEED = 1
RESAMPLES = 2000
MINIMUM_EFFECT = 0.1
MIN_BLOCKS_FOR_EVIDENCE = 80
ALLOWED_TABLES = {"spread", *CARD_MEASURES, "special-heavy", "special-heavy-revision"}
SPECIAL_BY_STYLE = {"special-heavy": SPECIAL, "special-heavy-revision": SPECIAL_REVISION}


_PairedCount = namedtuple("_PairedCount", ["players", "faction", "before", "after"])


_SeedTotal = namedtuple("_SeedTotal", ["before", "after", "games"])


def special_action(seat, style):
    table = SPECIAL_BY_STYLE.get(style)
    return table[seat["faction"]] if table is not None else None


def card_count(seat, cards):
    return sum(count for key, count in seat["cardsPlayed"].items() if key.partition("/")[0] in cards)


def measure(seat, style):
    action = special_action(seat, style)
    if action is None:
        return card_count(seat, CARD_MEASURES[style])
    return seat["specialAccepts"].get(action, 0)


def diagnostic_count(seat, style):
    action = special_action(seat, style)
    if action is not None:
        return seat.get("specialAttempts", {}).get(action, 0)
    if style == "information-oriented":
        return card_count(seat, {"DECOY"})
    return 0


def percentile(samples, probability):
    """Linearly interpolate the given quantile over the samples."""
    ordered = sorted(samples)
    position = probability * (len(ordered) - 1)
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def read_lines(path):
    return read_jsonl(path)


def _table_by_digest(manifest):
    for table in manifest["tables"]:
        if len({seat["policyDigest"] for seat in table["seats"]}) != 1:
            raise ValueError("Each study table must have one policy digest")
    table_by_digest = {table["seats"][0]["policyDigest"]: table["id"] for table in manifest["tables"]}
    names = set(table_by_digest.values())
    if "spread" not in names or len(names) != len(manifest["tables"]) or not names <= ALLOWED_TABLES:
        raise ValueError("Unexpected or duplicate study table digest")
    return table_by_digest


def _fact_by_id(facts):
    fact_by_id = {}
    for fact in facts:
        if fact["caseId"] in fact_by_id:
            raise ValueError(f"Duplicate facts for {fact['caseId']}")
        fact_by_id[fact["caseId"]] = fact
    return fact_by_id


def _index_cases(cases, table_by_digest):
    indexed = {}
    for case in cases:
        seats = sorted(case["seats"], key=lambda seat: seat["seatIndex"])
        digests = {seat["policyDigest"] for seat in seats}
        if len(digests) != 1:
            raise ValueError("Each study case must identify one homogeneous table")
        (digest,) = digests
        if digest not in table_by_digest:
            raise ValueError("Each study case must identify one homogeneous table")
        coordinate = (str(case["seed"]), case["playerCount"], tuple((s["seatIndex"], s["faction"]) for s in seats))
        key = (table_by_digest[digest], coordinate)
        if key in indexed:
            raise ValueError(f"Duplicate case coordinate: {key}")
        indexed[key] = case
    return indexed


def _rotations(ordered):
    return [ordered[shift:] + ordered[:shift] for shift in range(len(ordered))]


def _expected_coordinates(seed, faction_sets):
    return {(seed, len(ordered), tuple(enumerate(ordered)))
            for faction_set in faction_sets
            for ordered in _rotations(sorted(faction_set))}


def _seed_is_complete(seed, left, right, faction_sets, fact_by_id):
    expected = _expected_coordinates(seed, faction_sets)
    if left.keys() != right.keys() or left.keys() != expected:
        return False
    if any(sum(coord[1] == players for coord in left) != count for players, count in PER_SEED.items()):
        return False
    return all(case["state"] == "SUCCEEDED" and case["caseId"] in fact_by_id
               for case in (*left.values(), *right.values()))


def _collect_seed_rows(style, left, right, fact_by_id, rows, diagnostics):
    for coord in sorted(left):
        before = {s["seat"]: s for s in fact_by_id[left[coord]["caseId"]]["seats"]}
        after = {s["seat"]: s for s in fact_by_id[right[coord]["caseId"]]["seats"]}
        if set(before) != set(after) or set(before) != {seat for seat, _ in coord[2]}:
            raise ValueError(f"Fact seat mismatch for {coord}")
        for seat_index, faction in coord[2]:
            if before[seat_index]["faction"] != faction or after[seat_index]["faction"] != faction:
                raise ValueError(f"Fact faction mismatch for {coord}")
            rows.append(_PairedCount(coord[1], faction, measure(before[seat_index], style),
                                     measure(after[seat_index], style)))
            diagnostic_before = diagnostic_count(before[seat_index], style)
            diagnostic_after = diagnostic_count(after[seat_index], style)
            for players in (None, coord[1]):
                totals = diagnostics[faction, players]
                totals[0] += diagnostic_before
                totals[1] += diagnostic_after


def _bootstrap_interval(per_seed):
    randomizer = random.Random(ANALYSIS_SEED)
    samples = []
    for _ in range(RESAMPLES):
        drawn = randomizer.choices(per_seed, k=len(per_seed))
        samples.append(sum(candidate - baseline for baseline, candidate, _ in drawn)
                       / sum(games for _, _, games in drawn))
    return percentile(samples, 0.025), percentile(samples, 0.975)


def _cell_for(faction, players, blocks, diagnostics, style):
    per_seed = []
    for seed in sorted(blocks, key=int):
        rows = [row for row in blocks[seed] if row.faction == faction and (players is None or row.players == players)]
        per_seed.append(_SeedTotal(sum(row.before for row in rows), sum(row.after for row in rows), len(rows)))
    games = sum(total.games for total in per_seed)
    if len(per_seed) < 2 or not games:
        return {"faction": faction, "playerCount": players, "status": "INSUFFICIENT_SAMPLE"}
    baseline_total = sum(total.before for total in per_seed)
    candidate_total = sum(total.after for total in per_seed)
    cell = {"faction": faction, "playerCount": players, "blocks": len(per_seed),
                            "seatGamesPerSide": games, "baselineCount": baseline_total,
                            "candidateCount": candidate_total}
    diagnostic_before, diagnostic_after = diagnostics[faction, players]
    if style in SPECIAL_BY_STYLE:
        cell.update(baselineSpecialAttempts=diagnostic_before, candidateSpecialAttempts=diagnostic_after)
        if baseline_total + candidate_total == 0:
            return dict(cell, status="INSUFFICIENT_SAMPLE", reason="NO_ACCEPTED_PLAYS", passes=False)
    if style == "information-oriented":
        cell.update(baselineDecoyCount=diagnostic_before, candidateDecoyCount=diagnostic_after)
    low, high = _bootstrap_interval(per_seed)
    return dict(cell, effect=(candidate_total - baseline_total) / games, interval=[low, high],
                passes=len(per_seed) >= MIN_BLOCKS_FOR_EVIDENCE and low > MINIMUM_EFFECT)


def _analyze_style(style, baseline, candidate, faction_sets, expected_seeds, fact_by_id):
    blocks = defaultdict(list)
    combined_diagnostics = defaultdict(lambda: [0, 0])
    excluded = []
    for seed in sorted(expected_seeds, key=int):
        left = {coord: case for coord, case in baseline.items() if coord[0] == seed}
        right = {coord: case for coord, case in candidate.items() if coord[0] == seed}
        if not _seed_is_complete(seed, left, right, faction_sets, fact_by_id):
            excluded.append(seed)
            continue
        _collect_seed_rows(style, left, right, fact_by_id, blocks[seed], combined_diagnostics)
    cells = [_cell_for(faction, players, blocks, combined_diagnostics, style)
             for faction in FACTIONS for players in (None, 3, 4, 5)]
    return {"excludedSeeds": excluded, "cells": cells}


def analyze(manifest, cases, facts):
    table_by_digest = _table_by_digest(manifest)
    fact_by_id = _fact_by_id(facts)
    indexed = _index_cases(cases, table_by_digest)
    expected_seeds = set(manifest["seeds"])
    if any(seed not in expected_seeds for _, (seed, _, _) in indexed):
        raise ValueError("Unexpected seed")
    case_counts = {table: {"expected": len(expected_seeds) * sum(PER_SEED.values()),
                           "exported": 0, "nonSucceeded": 0} for table in set(table_by_digest.values())}
    for (table, _), case in indexed.items():
        case_counts[table]["exported"] += 1
        if case["state"] != "SUCCEEDED":
            case_counts[table]["nonSucceeded"] += 1
    baseline = {coord: case for (table, coord), case in indexed.items() if table == "spread"}
    styles = {}
    for style in sorted(set(table_by_digest.values()) - {"spread"}):
        candidate = {coord: case for (table, coord), case in indexed.items() if table == style}
        styles[style] = _analyze_style(style, baseline, candidate, manifest["factionSets"], expected_seeds, fact_by_id)
    return {"manifestSha256": manifest_sha256(manifest), "analysisSeed": ANALYSIS_SEED, "resamples": RESAMPLES,
            "minimumEffect": MINIMUM_EFFECT, "caseCountsByTable": dict(sorted(case_counts.items())), "styles": styles}


if __name__ == "__main__":
    if len(sys.argv) != 7:
        raise SystemExit(__doc__)
    manifest, experiment, report = (read_json(path) for path in sys.argv[1:4])
    cases = read_lines(sys.argv[4])
    check_run(manifest, experiment, report, cases)
    result = analyze(manifest, cases, read_lines(sys.argv[5]))
    result["source"] = {**source_identity(report, sys.argv[3], cases), "factsSha256": file_sha256(sys.argv[5])}
    write_json(sys.argv[6], result)
