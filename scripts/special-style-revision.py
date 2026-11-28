#!/usr/bin/env python3
"""Reproduce the exploratory secondary-special revision on fresh seeds.

Usage: python scripts/special-style-revision.py POLICIES.json BUNDLES.json OUTPUT.json
"""
import importlib.util
import json
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location("study", Path(__file__).with_name("play-style-study.py"))
study = importlib.util.module_from_spec(spec)
spec.loader.exec_module(study)


def revision(policies, bundles):
    manifest = study.make_manifest(policies, bundles)
    search = next(p for p in policies["items"] if p["id"] == "faction-search" and p["version"] == "1.0.0")
    schema = {p["key"]: p for p in search["parameterDefinitions"]}
    actions = dict(zip(study.FACTIONS, ("CORRUPT", "SEAL", "OBSCURE", "REWEAVE", "EXPOSE")))
    parameters = {}
    for faction, action in actions.items():
        key = f"affinity.special.{faction}.{action}"
        item = schema[key]
        if not (item["min"] <= 8 <= item["max"] and (8 - item["min"]) % item["step"] == 0):
            raise ValueError(f"{key}: value 8 is outside the served grid")
        if item["defaultValue"] != 8:
            parameters[key] = 8
    digest = study.digest(parameters)
    baseline = manifest["policies"][0]
    candidate = {"id": "faction-search", "version": "1.0.0", "artifactDigest": digest,
                 "parameters": dict(sorted(parameters.items()))}
    manifest["name"] = "Secondary special play style validation"
    manifest["seeds"] = [str(seed) for seed in range(43600, 43700)]
    manifest["policies"] = [baseline, candidate]
    manifest["tables"] = [manifest["tables"][0], {"id": "special-heavy-revision", "seats": [
        {"seatIndex": i, "policyId": candidate["id"], "policyVersion": candidate["version"],
         "policyDigest": digest} for i in range(5)]}]
    return manifest


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    policies, bundles = (json.loads(Path(p).read_text(encoding="utf-8")) for p in sys.argv[1:3])
    Path(sys.argv[3]).write_text(json.dumps(revision(policies, bundles), indent=2) + "\n", encoding="utf-8", newline="\n")
