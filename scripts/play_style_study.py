#!/usr/bin/env python3
"""Construct the preregistered homogeneous comparison from discovery responses.

Usage: python scripts/play_style_study.py POLICIES.json BUNDLES.json MANIFEST.json
The input files are unmodified responses from the workbench discovery endpoints.
"""

import hashlib
import itertools
import sys

from play_style_evidence import read_json, write_json

FACTIONS = ("ERASERS", "PROPHETS", "REVISIONISTS", "WEAVERS", "ACTIVISTS")
SPECIALS = dict(zip(FACTIONS, ("ANNIHILATE", "FORESIGHT", "REWRITE", "THREAD", "EXPOSE")))
# These special scores use a counter or protection threshold for every target.
UNUSED_SPECIAL_AFFINITIES = {
    "affinity.special.ACTIVISTS.EXPOSE",
    "affinity.special.ERASERS.CORRUPT",
    "affinity.special.WEAVERS.REWEAVE",
    "affinity.special.WEAVERS.TAPESTRY",
}

BASE_AFFINITY = 8
SUPPRESS_LEAN = 1
TABLE_SEATS = 5
INFORMATION_CARDS = ("SCAN", "INTERCEPT", "TRACE", "DECOY")


def digest(parameters):
    canonical = ",".join(f"{key}={value}" for key, value in sorted(parameters.items()))
    return hashlib.sha256(f"faction-search|1.0.0|1|{canonical}".encode()).hexdigest()


def _check_grid_value(spec, key, value):
    if not (spec["min"] <= value <= spec["max"] and (value - spec["min"]) % spec["step"] == 0):
        raise ValueError(f"{key}: {value} is outside the served grid")


def _overrides(schema, values):
    overrides = {}
    for key, value in values.items():
        _check_grid_value(schema[key], key, value)
        if value != schema[key]["defaultValue"]:
            overrides[key] = value
    return dict(sorted(overrides.items()))


def candidates(schema):
    proposed = {"cautious": {}, "contrarian": {}, "special-heavy": {}, "information-oriented": {}}
    for faction in FACTIONS:
        proposed["cautious"][f"affinity.card.{faction}.NULLIFY"] = BASE_AFFINITY
        proposed["contrarian"][f"affinity.card.{faction}.SUPPRESS"] = BASE_AFFINITY
        proposed["contrarian"][f"lean.suppress.{faction}"] = SUPPRESS_LEAN
        proposed["special-heavy"][f"affinity.special.{faction}.{SPECIALS[faction]}"] = BASE_AFFINITY
        for card in INFORMATION_CARDS:
            proposed["information-oriented"][f"affinity.card.{faction}.{card}"] = BASE_AFFINITY
    return {name: _overrides(schema, values) for name, values in proposed.items()}


def _policy_by_id(policies, policy_id, version):
    return next(item for item in policies["items"] if item["id"] == policy_id and item["version"] == version)


def make_manifest(policies, bundles):
    search = _policy_by_id(policies, "faction-search", "1.0.0")
    spread = _policy_by_id(policies, "faction-spread", "1.0.0")
    if search["artifactDigest"] != digest({}):
        raise ValueError("The served search bundle has a different digest algorithm or behavior revision")
    styles = candidates({item["key"]: item for item in search["parameterDefinitions"]})
    entries = [{"id": spread["id"], "version": spread["version"],
                "artifactDigest": spread["artifactDigest"], "parameters": {}}]
    entries.extend({"id": "faction-search", "version": "1.0.0", "artifactDigest": digest(params), "parameters": params}
                   for params in styles.values())
    if len(bundles["items"]) != 1:
        raise ValueError("Expected exactly one lane bundle; select the intended bundle explicitly")
    bundle = bundles["items"][0]
    tables = [{"id": name, "seats": [{"seatIndex": seat, "policyId": entry["id"],
                                      "policyVersion": entry["version"], "policyDigest": entry["artifactDigest"]}
                                     for seat in range(TABLE_SEATS)]}
              for name, entry in zip(("spread", *styles), entries)]
    return {"schemaVersion": 2, "name": "Fixed play style validation",
            "variants": [{"label": "baseline", **{key: bundle[key] for key in
                         ("rulesArtifact", "contentArtifact", "effectiveRulesDigest", "effectiveContentDigest")}}],
            "services": bundle["services"], "contracts": bundle["contracts"],
            "seeds": [str(seed) for seed in range(42000, 42100)], "playerCounts": [3, 4, 5],
            "factionSets": [list(group) for size in (3, 4, 5) for group in itertools.combinations(FACTIONS, size)],
            "seatRotationMode": "CYCLIC", "timingMode": "LOGICAL", "policies": entries,
            "tables": tables, "concurrency": 12, "caseWallTimeoutSeconds": 3600,
            "maxRejectedCandidatesPerWindow": 10}


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    policy_data = read_json(sys.argv[1])
    bundle_data = read_json(sys.argv[2])
    write_json(sys.argv[3], make_manifest(policy_data, bundle_data))
