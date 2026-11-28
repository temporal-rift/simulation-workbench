#!/usr/bin/env python3
"""Validate completed studies and write the versioned population data.

Usage: python scripts/publish-play-style-population.py INITIAL REVISION FINAL RULES CONFIG OUTPUT
"""
import shutil
import sys
from pathlib import Path

from play_style_evidence import (
    faction_coverage,
    file_sha256,
    load_sibling,
    read_json,
    read_jsonl,
    source_identity,
    write_json,
)

study = load_sibling(__file__, "play-style-study.py", "study")
merger = load_sibling(__file__, "merge-play-style-studies.py", "merger")
checker = load_sibling(__file__, "check-mixed-play-styles.py", "checker")
mixed = load_sibling(__file__, "mixed-play-style-tables.py", "mixed")


def _load_combined(initial, revision):
    first = read_json(initial / "manifest.json")
    second = read_json(revision / "manifest.json")
    manifest, results = merger.merge(first, second,
                                     read_json(initial / "results.json"), read_json(revision / "results.json"))
    return first, manifest, results


def _validated_presets(manifest, results):
    by_digest = {policy["artifactDigest"]: policy for policy in manifest["policies"]}
    if len(by_digest) != len(manifest["policies"]):
        raise ValueError("Duplicate policy digest in combined study")
    factions = {faction for group in manifest["factionSets"] for faction in group}
    presets = []
    for table in manifest["tables"]:
        name = table["id"]
        if name == "spread" or faction_coverage(results["styles"][name]["cells"]) != factions:
            continue
        digests = {seat["policyDigest"] for seat in table["seats"]}
        if len(digests) != 1:
            raise ValueError(f"Non-homogeneous validation table {name}")
        (digest,) = digests
        policy = by_digest[digest]
        if policy["artifactDigest"] != study.digest(policy["parameters"]):
            raise ValueError(f"Policy digest mismatch for {name}")
        if set(policy["parameters"]) & study.UNUSED_SPECIAL_AFFINITIES:
            raise ValueError(f"Unused special affinity in {name}")
        presets.append({"name": name, "version": "1.0.0",
                        "factionCoverage": [faction for faction in study.FACTIONS if faction in factions],
                        "policy": policy})
    if not 3 <= len(presets) <= 5:
        raise ValueError("Need between 3 and 5 validated presets")
    return presets


def _verify_mixed(mixed_source, manifest, results):
    expected_manifests = mixed.manifests(manifest, results)
    evidence = {}
    for players in (3, 4, 5):
        name = f"mixed-{players}"
        mixed_manifest = read_json(mixed_source / "mixed" / f"{name}.json")
        if mixed_manifest != expected_manifests[players]:
            raise ValueError(f"{name} differs from the expected mixed manifest")
        cases = read_jsonl(mixed_source / f"{name}-cases.jsonl")
        report_path = mixed_source / f"{name}-report.json"
        report = read_json(report_path)
        observed = checker.check(mixed_manifest, read_json(mixed_source / f"{name}-experiment.json"), report, cases)
        if observed != read_json(mixed_source / f"{name}-exposure.json"):
            raise ValueError(f"{name} exposure summary mismatch")
        evidence[name] = {**source_identity(report, report_path, cases), **observed}
    return evidence


def _write_outputs(output, initial, revision, mixed_source, rules, config, presets, mixed_evidence):
    output.mkdir(parents=True, exist_ok=True)
    write_json(output / "presets.json", {"policyParameterSpaceVersion": 1, "presets": presets})
    write_json(output / "mixed-evidence.json", mixed_evidence)
    for directory, prefix in ((initial, "initial"), (revision, "revision")):
        shutil.copyfile(directory / "manifest.json", output / f"{prefix}-validation.json")
        shutil.copyfile(directory / "results.json", output / f"{prefix}-measurements.json")
    for players in (3, 4, 5):
        name = f"mixed-{players}"
        shutil.copyfile(mixed_source / "mixed" / f"{name}.json", output / f"{name}.json")
        shutil.copyfile(mixed_source / f"{name}-exposure.json", output / f"{name}-exposure.json")
    shutil.copyfile(rules, output / "effective-rules.json")
    shutil.copyfile(config, output / "simulator-config.yml")


def publish(initial, revision, final, rules, config, output):
    if not all((directory / "completed").is_file() for directory in (initial, revision)):
        raise ValueError("Both study runs must complete before publication")
    first, manifest, results = _load_combined(initial, revision)
    if file_sha256(rules) != first["variants"][0]["rulesArtifact"]["digest"]:
        raise ValueError("The rules artifact does not match the study manifest")
    presets = _validated_presets(manifest, results)
    revised = any(preset["name"] == "special-heavy-revision" for preset in presets)
    mixed_source = final if revised else initial
    if not (mixed_source / "completed").is_file():
        raise ValueError("The selected mixed verification run must complete before publication")
    mixed_evidence = _verify_mixed(mixed_source, manifest, results)
    _write_outputs(output, initial, revision, mixed_source, rules, config, presets, mixed_evidence)
    return len(presets)


if __name__ == "__main__":
    if len(sys.argv) != 7:
        raise SystemExit(__doc__)
    print(f"Published {publish(*map(Path, sys.argv[1:]))} validated presets")
