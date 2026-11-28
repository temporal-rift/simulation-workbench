#!/usr/bin/env python3
"""Build matched P3 recording manifests across spread, greedy and mixed presets.

Usage: python scripts/cross_style_baseline.py POLICIES.json BUNDLES.json PRESETS.json OUTPUT_DIR
       [--seed-base 9300000 --blocks 3:10 --blocks 4:10 --blocks 5:40]
The input files are unmodified discovery responses plus the published preset
population. Each player count gets one manifest with five tables (homogeneous
spread, homogeneous greedy, three rotating mixed tables) sharing one variant,
one seed block and one rotation scheme.
"""

import argparse
import itertools
import sys
from pathlib import Path

from play_style_evidence import read_json, write_json
from play_style_study import FACTIONS

P3_OVERRIDES = {"game.rules.victory.normal-victory": False, "game.rules.victory.weaver-chain-length": 3}
PARAMETER_SPACE_VERSION = 3
SIZE_OFFSETS = {3: 0, 4: 100000, 5: 200000}
DEFAULT_BLOCKS = {3: 10, 4: 10, 5: 40}


def parse_blocks(values):
    blocks = dict(DEFAULT_BLOCKS)
    for value in values:
        try:
            players, count = (int(part) for part in value.split(":"))
        except ValueError:
            raise ValueError(f"Blocks must look like 3:10, got {value!r}")
        if players not in SIZE_OFFSETS or count < 2:
            raise ValueError(f"Need 3, 4 or 5 players and at least 2 blocks, got {value!r}")
        blocks[players] = count
    return blocks


def _policy_by_id(policies, policy_id):
    matches = [item for item in policies["items"] if item["id"] == policy_id]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one {policy_id} policy in discovery response")
    return matches[0]


def _homogeneous_table(table_id, policy, players):
    return {"id": table_id, "seats": [{"seatIndex": seat, "policyId": policy["id"],
                                       "policyVersion": policy["version"],
                                       "policyDigest": policy["artifactDigest"]}
                                      for seat in range(players)]}


def _mixed_tables(styles, players):
    tables = []
    for shift in range(len(styles)):
        seats = []
        for seat in range(players):
            policy = styles[(seat + shift) % len(styles)]
            seats.append({"seatIndex": seat, "policyId": policy["id"],
                          "policyVersion": policy["version"], "policyDigest": policy["artifactDigest"]})
        tables.append({"id": f"mixed-{shift + 1}", "seats": seats})
    return tables


def build_manifest(policies_data, bundles_data, presets_data, players, seed_base, blocks):
    if players not in SIZE_OFFSETS:
        raise ValueError(f"Player count must be 3, 4 or 5, got {players}")
    spread = _policy_by_id(policies_data, "faction-spread")
    greedy = _policy_by_id(policies_data, "faction-greedy")
    styles = [preset["policy"] for preset in presets_data["presets"]]
    if not 3 <= len(styles) <= 5:
        raise ValueError(f"Need 3 to 5 presets, found {len(styles)}")
    if len(bundles_data["items"]) != 1:
        raise ValueError("Expected exactly one lane bundle; select the intended bundle explicitly")
    bundle = bundles_data["items"][0]
    seen = set()
    policies = []
    for policy in ({"id": spread["id"], "version": spread["version"],
                    "artifactDigest": spread["artifactDigest"], "parameters": {}},
                   {"id": greedy["id"], "version": greedy["version"],
                    "artifactDigest": greedy["artifactDigest"], "parameters": {}},
                   *styles):
        if policy["artifactDigest"] not in seen:
            seen.add(policy["artifactDigest"])
            policies.append(policy)
    tables = [_homogeneous_table("spread", policies[0], players),
              _homogeneous_table("greedy", policies[1], players),
              *_mixed_tables(styles, players)]
    return {"schemaVersion": 2, "name": f"P3 cross-style baseline: {players} players",
            "variants": [{"label": "p3-recording",
                          "rulesArtifact": bundle["rulesArtifact"],
                          "contentArtifact": bundle["contentArtifact"],
                          "effectiveRulesDigest": bundle["effectiveRulesDigest"],
                          "effectiveContentDigest": bundle["effectiveContentDigest"],
                          "parameterSpaceVersion": PARAMETER_SPACE_VERSION,
                          "overrides": dict(P3_OVERRIDES)}],
            "services": bundle["services"], "contracts": bundle["contracts"],
            "seeds": [str(seed_base + SIZE_OFFSETS[players] + index) for index in range(blocks)],
            "playerCounts": [players],
            "factionSets": [list(group) for group in itertools.combinations(FACTIONS, players)],
            "seatRotationMode": "CYCLIC", "timingMode": "LOGICAL", "policies": policies,
            "tables": tables, "concurrency": 12, "caseWallTimeoutSeconds": 3600,
            "maxRejectedCandidatesPerWindow": 10}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("policies", type=Path)
    parser.add_argument("bundles", type=Path)
    parser.add_argument("presets", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--seed-base", type=int, default=9300000)
    parser.add_argument("--blocks", action="append", default=[],
                        help="PlayerCount:blocks, repeatable (default 3:10 4:10 5:40)")
    args = parser.parse_args(argv)
    blocks = parse_blocks(args.blocks)
    policies_data = read_json(args.policies)
    bundles_data = read_json(args.bundles)
    presets_data = read_json(args.presets)
    args.output.mkdir(parents=True, exist_ok=True)
    for players in sorted(blocks):
        manifest = build_manifest(policies_data, bundles_data, presets_data,
                                  players, args.seed_base, blocks[players])
        write_json(args.output / f"recording-{players}p.json", manifest)
    return 0


if __name__ == "__main__":
    sys.exit(main())
