#!/usr/bin/env python3
"""Validate completed studies and write the versioned population data.

Usage: python scripts/publish-play-style-population.py INITIAL REVISION FINAL RULES CONFIG OUTPUT
"""
import hashlib
import importlib.util
import json
import shutil
import sys
from pathlib import Path

from play_style_evidence import faction_coverage, source_identity


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


study = module("study", "play-style-study.py")
merger = module("merger", "merge-play-style-studies.py")
checker = module("checker", "check-mixed-play-styles.py")
mixed = module("mixed", "mixed-play-style-tables.py")


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def publish(initial, revision, final, rules, config, output):
    if not all((directory / "completed").is_file() for directory in (initial, revision)):
        raise ValueError("Both study runs must complete before publication")
    first, second = read(initial / "manifest.json"), read(revision / "manifest.json")
    first_results, second_results = read(initial / "results.json"), read(revision / "results.json")
    manifest, results = merger.merge(first, second, first_results, second_results)
    if hashlib.sha256(rules.read_bytes()).hexdigest() != first["variants"][0]["rulesArtifact"]["digest"]:
        raise ValueError("The rules artifact does not match the study manifest")
    by_digest = {p["artifactDigest"]: p for p in manifest["policies"]}
    if len(by_digest) != len(manifest["policies"]):
        raise ValueError("Duplicate policy digest in combined study")
    factions = {faction for group in manifest["factionSets"] for faction in group}
    presets = []
    for table in manifest["tables"]:
        name = table["id"]
        if name == "spread":
            continue
        passed = faction_coverage(results["styles"][name]["cells"])
        if passed == factions:
            digests = {seat["policyDigest"] for seat in table["seats"]}
            if len(digests) != 1:
                raise ValueError(f"Non-homogeneous validation table {name}")
            policy = by_digest[digests.pop()]
            if policy["artifactDigest"] != study.digest(policy["parameters"]):
                raise ValueError(f"Policy digest mismatch for {name}")
            if set(policy["parameters"]) & study.UNUSED_SPECIAL_AFFINITIES:
                raise ValueError(f"Unused special affinity in {name}")
            presets.append({"name": name, "version": "1.0.0",
                            "factionCoverage": [faction for faction in study.FACTIONS if faction in passed],
                            "policy": policy})
    if not 3 <= len(presets) <= 5:
        raise ValueError("Need between 3 and 5 validated presets")
    revised = any(p["name"] == "special-heavy-revision" for p in presets)
    mixed_source = final if revised else initial
    if not (mixed_source / "completed").is_file():
        raise ValueError("The selected mixed verification run must complete before publication")
    expected_manifests = mixed.manifests(manifest, results)
    mixed_evidence = {}
    for players in (3, 4, 5):
        name = f"mixed-{players}"
        mixed_manifest = read(mixed_source / "mixed" / f"{name}.json")
        if mixed_manifest != expected_manifests[players]:
            raise ValueError(f"{name} differs from the expected mixed manifest")
        with (mixed_source / f"{name}-cases.jsonl").open(encoding="utf-8") as source:
            cases = [json.loads(line) for line in source if line.strip()]
        report_path = mixed_source / f"{name}-report.json"
        report = read(report_path)
        observed = checker.check(mixed_manifest, read(mixed_source / f"{name}-experiment.json"), report, cases)
        if observed != read(mixed_source / f"{name}-exposure.json"):
            raise ValueError(f"{name} exposure summary mismatch")
        mixed_evidence[name] = {**source_identity(report, report_path, cases), **observed}
    output.mkdir(parents=True, exist_ok=True)
    (output / "presets.json").write_text(json.dumps({"policyParameterSpaceVersion": 1, "presets": presets}, indent=2) + "\n", encoding="utf-8", newline="\n")
    (output / "mixed-evidence.json").write_text(json.dumps(mixed_evidence, indent=2) + "\n",
                                              encoding="utf-8", newline="\n")
    for directory, prefix in ((initial, "initial"), (revision, "revision")):
        shutil.copyfile(directory / "manifest.json", output / f"{prefix}-validation.json")
        shutil.copyfile(directory / "results.json", output / f"{prefix}-measurements.json")
    for players in (3, 4, 5):
        name = f"mixed-{players}"
        shutil.copyfile(mixed_source / "mixed" / f"{name}.json", output / f"{name}.json")
        shutil.copyfile(mixed_source / f"{name}-exposure.json", output / f"{name}-exposure.json")
    shutil.copyfile(rules, output / "effective-rules.json")
    shutil.copyfile(config, output / "simulator-config.yml")
    return len(presets)


if __name__ == "__main__":
    if len(sys.argv) != 7:
        raise SystemExit(__doc__)
    print(f"Published {publish(*map(Path, sys.argv[1:]))} validated presets")
