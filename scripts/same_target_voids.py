#!/usr/bin/env python3
"""Find same-target void groups by crossing commitments with settlements.

Usage: python scripts/same_target_voids.py CASES.jsonl EVENTS.jsonl OUTPUT.json
CASES.jsonl rows come from cross-style-cases.sql; EVENTS.jsonl rows from
cross-style-evidence.sql. Commitments are SpecialActionPlayed and
ActivistDeclarationRecorded payloads; settlements are ScoresUpdated updates.
A commitment is voided when its seat-era lists no win or miss reason of its
faction. A group shares (game, era, target) with voided commitments from at
least two distinct seats; target-less commitments cannot form a group. A group
is verifiable when every voided seat has settlement records for that era,
otherwise its evidence is incomplete. Erasure is attributed only when the same
game and era records ANNIHILATED_OUTCOME.
"""

import sys
from collections import defaultdict

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


def _index_cases(cases):
    indexed = {}
    for case in cases:
        if case["state"] != "SUCCEEDED" or not case.get("gameId"):
            continue
        indexed[case["caseId"]] = {"game": case["gameId"],
                                   "seats": {seat["faction"]: seat["seatIndex"] for seat in case["seats"]}}
    return indexed


def _collect(rows):
    factions, commitments, settlements = {}, [], defaultdict(set)
    for row in rows:
        payload = row["payload"]
        if row["type"] == "FactionAssigned":
            factions[payload["playerId"]] = payload["faction"]
        elif row["type"] in ("SpecialActionPlayed", "ActivistDeclarationRecorded"):
            commitments.append({"case": row["caseId"], "game": row["gameId"], "player": payload["playerId"],
                                "era": payload["eraNumber"],
                                "action": payload.get("specialAction", payload.get("mode")),
                                "target": (payload.get("targetEventId"), payload.get("targetOutcomeId"),
                                           payload.get("targetPlayerId"))})
        elif row["type"] == "ScoresUpdated":
            for update in payload["updates"]:
                settlements[(row["gameId"], update["playerId"], payload["eraNumber"])].add(update["reason"])
    return factions, commitments, settlements


def _voided(commitments, factions, settlements):
    voided = []
    for commitment in commitments:
        faction = factions.get(commitment["player"])
        if faction is None:
            continue
        wins, misses = BET_REASONS[faction]
        reasons = settlements.get((commitment["game"], commitment["player"], commitment["era"]), set())
        if not (reasons & wins) and not (reasons & misses):
            voided.append(commitment)
    return voided


def find_groups(cases, rows):
    seats_of = _index_cases(cases)
    factions, commitments, settlements = _collect(rows)
    buckets = defaultdict(list)
    for commitment in _voided(commitments, factions, settlements):
        if commitment["case"] not in seats_of or all(part is None for part in commitment["target"]):
            continue
        buckets[(commitment["game"], commitment["era"]) + commitment["target"]].append(commitment)
    groups = []
    for (game, era, event, outcome, player), members in sorted(buckets.items()):
        players = sorted({member["player"] for member in members})
        if len(players) < 2:
            continue
        seats = seats_of[members[0]["case"]]["seats"]
        verifiable = all(settlements.get((game, player, era)) for player in players)
        erasure = any("ANNIHILATED_OUTCOME" in reasons
                      for (settled_game, _, settled_era), reasons in settlements.items()
                      if settled_game == game and settled_era == era)
        groups.append({"game": game, "era": era, "targetEvent": event, "targetOutcome": outcome,
                       "targetPlayer": player,
                       "seats": sorted(seats[faction] for faction in
                                       {factions[player] for player in players if player in factions}),
                       "players": players, "verifiable": verifiable,
                       "erasureAttributed": erasure and verifiable})
    return groups


def summarize(groups, cases):
    verifiable = [group for group in groups if group["verifiable"]]
    return {"games": len(_index_cases(cases)), "groups": len(groups),
            "verifiableGroups": len(verifiable), "incompleteGroups": len(groups) - len(verifiable),
            "erasureAttributedGroups": sum(1 for group in verifiable if group["erasureAttributed"])}


def analyze(cases, rows):
    groups = find_groups(cases, rows)
    return {"groups": groups, "summary": summarize(groups, cases)}


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    write_json(sys.argv[3], analyze(read_jsonl(sys.argv[1]), read_jsonl(sys.argv[2])))
