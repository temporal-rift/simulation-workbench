#!/usr/bin/env python3
"""Reproduce the exploratory secondary-special revision on fresh seeds.

Usage: python scripts/special_style_revision.py POLICIES.json BUNDLES.json OUTPUT.json
"""
import sys

import play_style_study as study

from play_style_evidence import read_json, write_json

SECONDARY_SPECIALS = dict(zip(study.FACTIONS, ("CORRUPT", "SEAL", "OBSCURE", "REWEAVE", "EXPOSE")))
AFFINITY_VALUE = 8


def revision(policies, bundles):
    manifest = study.make_manifest(policies, bundles)
    search = next(item for item in policies["items"] if item["id"] == "faction-search" and item["version"] == "1.0.0")
    schema = {item["key"]: item for item in search["parameterDefinitions"]}
    parameters = {}
    for faction, action in SECONDARY_SPECIALS.items():
        key = f"affinity.special.{faction}.{action}"
        item = schema[key]
        if not (item["min"] <= AFFINITY_VALUE <= item["max"] and (AFFINITY_VALUE - item["min"]) % item["step"] == 0):
            raise ValueError(f"{key}: value {AFFINITY_VALUE} is outside the served grid")
        if item["defaultValue"] != AFFINITY_VALUE:
            parameters[key] = AFFINITY_VALUE
    candidate = {"id": "faction-search", "version": "1.0.0", "artifactDigest": study.digest(parameters),
                 "parameters": dict(sorted(parameters.items()))}
    manifest.update(name="Secondary special play style validation",
                    seeds=[str(seed) for seed in range(43600, 43700)],
                    policies=[manifest["policies"][0], candidate])
    manifest["tables"] = [manifest["tables"][0], {"id": "special-heavy-revision", "seats": [
        {"seatIndex": seat, "policyId": candidate["id"], "policyVersion": candidate["version"],
         "policyDigest": candidate["artifactDigest"]} for seat in range(5)]}]
    return manifest


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    policies = read_json(sys.argv[1])
    bundles = read_json(sys.argv[2])
    write_json(sys.argv[3], revision(policies, bundles))
