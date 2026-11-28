#!/usr/bin/env python3
"""Find same-target void groups by crossing commitments with settlements.

Usage: python scripts/same_target_voids.py GAMES.jsonl OUTPUT.json
Each input line describes one game: {"game", "seats": [{"seat", "faction"}],
"commitments": [{"seat", "era", "event", "outcome"}],
"settlements": [{"seat", "era", "reason"}]}. A commitment is voided when its
seat-era lists no win or miss reason of its faction. A group is games sharing
(game, era, event, outcome) with voided commitments from at least two distinct
seats. A group is verifiable when every voided seat has settlement records for
that era; otherwise its evidence is incomplete. Erasure is attributed only when
the same game and era records ANNIHILATED_OUTCOME.
"""

import sys

from play_style_evidence import read_jsonl, write_json

BET_REASONS = {
    "ERASERS": ({"ANNIHILATED_OUTCOME"}, {"ANNIHILATION_WASTED"}),
    "PROPHETS": ({"EVENT_RESOLVED_AS_WRITTEN", "FULFILLMENT_SUCCEEDED"},
                 {"EVENT_RESOLVED_DIFFERENTLY_THAN_WRITTEN"}),
    "REVISIONISTS": ({"SECRET_OUTCOME_WON"}, {"SECRET_OUTCOME_MISSED"}),
    "WEAVERS": ({"CHAIN_LINK_ADDED"}, {"CHAIN_LINK_MISSED"}),
    "ACTIVISTS": ({"DECLARED_OUTCOME_WON", "DECLARED_OUTCOME_WON_WITH_RALLY"},
                  {"DECLARED_OUTCOME_MISSED", "DECLARED_OUTCOME_MISSED_WITH_RALLY"}),
}


def _settled_reasons(game):
    settled = {}
    for record in game.get("settlements", []):
        settled.setdefault((record["seat"], record["era"]), set()).add(record["reason"])
    return settled


def _voided_commitments(game):
    factions = {seat["seat"]: seat["faction"] for seat in game.get("seats", [])}
    settled = _settled_reasons(game)
    voided = []
    for commitment in game.get("commitments", []):
        reasons = settled.get((commitment["seat"], commitment["era"]), set())
        wins, misses = BET_REASONS[factions[commitment["seat"]]]
        if not (reasons & wins) and not (reasons & misses):
            voided.append(commitment)
    return voided, settled


def find_groups(games):
    groups = []
    for game in games:
        voided, settled = _voided_commitments(game)
        buckets = {}
        for commitment in voided:
            key = (commitment["era"], commitment["event"], commitment["outcome"])
            buckets.setdefault(key, []).append(commitment["seat"])
        for (era, event, outcome), seats in sorted(buckets.items()):
            if len(set(seats)) < 2:
                continue
            verifiable = all(settled.get((seat, era)) for seat in set(seats))
            erasure = any("ANNIHILATED_OUTCOME" in reasons
                          for (_, seat_era), reasons in settled.items() if seat_era == era)
            groups.append({"game": game["game"], "era": era, "event": event, "outcome": outcome,
                           "seats": sorted(set(seats)), "verifiable": verifiable,
                           "erasureAttributed": erasure and verifiable})
    return groups


def summarize(groups, games):
    verifiable = [group for group in groups if group["verifiable"]]
    return {"games": len(games), "groups": len(groups), "verifiableGroups": len(verifiable),
            "incompleteGroups": len(groups) - len(verifiable),
            "erasureAttributedGroups": sum(1 for group in verifiable if group["erasureAttributed"])}


def analyze(games):
    groups = find_groups(games)
    return {"groups": groups, "summary": summarize(groups, games)}


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    write_json(sys.argv[2], analyze(read_jsonl(sys.argv[1])))
