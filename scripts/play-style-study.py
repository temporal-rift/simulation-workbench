#!/usr/bin/env python3
"""Construct the preregistered homogeneous comparison from discovery responses.

Usage: python scripts/play-style-study.py POLICIES.json BUNDLES.json MANIFEST.json
The input files are unmodified responses from the workbench discovery endpoints.
"""

import hashlib
import itertools
import json
import sys
from pathlib import Path

FACTIONS = ("ERASERS", "PROPHETS", "REVISIONISTS", "WEAVERS", "ACTIVISTS")
SPECIALS = dict(zip(FACTIONS, ("ANNIHILATE", "FORESIGHT", "REWRITE", "THREAD", "EXPOSE")))
# These special scores use a counter or protection threshold for every target.
UNUSED_SPECIAL_AFFINITIES = {
    "affinity.special.ACTIVISTS.EXPOSE",
    "affinity.special.ERASERS.CORRUPT",
    "affinity.special.WEAVERS.REWEAVE",
    "affinity.special.WEAVERS.TAPESTRY",
}


def digest(parameters):
    canonical = ",".join(f"{key}={parameters[key]}" for key in sorted(parameters))
    return hashlib.sha256(f"faction-search|1.0.0|1|{canonical}".encode()).hexdigest()


def candidates(schema):
    proposed = {"cautious": {}, "contrarian": {}, "special-heavy": {}, "information-oriented": {}}
    for faction in FACTIONS:
        proposed["cautious"][f"affinity.card.{faction}.NULLIFY"] = 8
        proposed["contrarian"][f"affinity.card.{faction}.SUPPRESS"] = 8
        proposed["contrarian"][f"lean.suppress.{faction}"] = 1
        proposed["special-heavy"][f"affinity.special.{faction}.{SPECIALS[faction]}"] = 8
        for card in ("SCAN", "INTERCEPT", "TRACE", "DECOY"):
            proposed["information-oriented"][f"affinity.card.{faction}.{card}"] = 8
    result = {}
    for name, values in proposed.items():
        overrides = {}
        for key, value in values.items():
            spec = schema[key]
            if not (spec["min"] <= value <= spec["max"] and (value - spec["min"]) % spec["step"] == 0):
                raise ValueError(f"{key}: {value} is outside the served grid")
            if value != spec["defaultValue"]:
                overrides[key] = value
        result[name] = dict(sorted(overrides.items()))
    return result


def make_manifest(policies, bundles):
    search = next(p for p in policies["items"] if p["id"] == "faction-search" and p["version"] == "1.0.0")
    spread = next(p for p in policies["items"] if p["id"] == "faction-spread" and p["version"] == "1.0.0")
    if search["artifactDigest"] != digest({}):
        raise ValueError("The served search bundle has a different digest algorithm or behavior revision")
    schema = {p["key"]: p for p in search["parameterDefinitions"]}
    styles = candidates(schema)
    entries = [{"id": spread["id"], "version": spread["version"],
                "artifactDigest": spread["artifactDigest"], "parameters": {}}]
    entries += [{"id": "faction-search", "version": "1.0.0", "artifactDigest": digest(params),
                 "parameters": params} for params in styles.values()]
    if len(bundles["items"]) != 1:
        raise ValueError("Expected exactly one lane bundle; select the intended bundle explicitly")
    bundle = bundles["items"][0]
    tables = []
    for name, entry in zip(("spread", *styles), entries):
        tables.append({"id": name, "seats": [{"seatIndex": seat, "policyId": entry["id"],
                       "policyVersion": entry["version"], "policyDigest": entry["artifactDigest"]}
                      for seat in range(5)]})
    return {"schemaVersion": 2, "name": "Fixed play style validation",
            "variants": [{"label": "baseline", **{k: bundle[k] for k in
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
    policy_data, bundle_data = (json.loads(Path(p).read_text(encoding="utf-8")) for p in sys.argv[1:3])
    Path(sys.argv[3]).write_text(json.dumps(make_manifest(policy_data, bundle_data), indent=2) + "\n", encoding="utf-8", newline="\n")
