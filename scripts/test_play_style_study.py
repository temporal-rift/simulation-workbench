import hashlib
import itertools
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from play_style_evidence import load_sibling, manifest_sha256

study = load_sibling(__file__, "play-style-study.py", "study")
analysis = load_sibling(__file__, "analyze-play-styles.py", "analysis")
mixed = load_sibling(__file__, "mixed-play-style-tables.py", "mixed")
mixed_check = load_sibling(__file__, "check-mixed-play-styles.py", "mixed_check")
merge = load_sibling(__file__, "merge-play-style-studies.py", "merge")
publish = load_sibling(__file__, "publish-play-style-population.py", "publish")


def pooled_passes(factions=analysis.FACTIONS):
    return [{"faction": faction, "playerCount": None, "blocks": 80, "interval": [0.2, 0.3], "passes": True}
            for faction in factions]


class PlayStyleStudyTest(unittest.TestCase):
    def fixture(self):
        factions = list(analysis.FACTIONS)
        groups = [list(c) for n in (3, 4, 5) for c in itertools.combinations(factions, n)]
        names = ["spread", "cautious", "contrarian", "special-heavy", "information-oriented"]
        tables = [{"id": name, "seats": [{"policyDigest": name}]} for name in names]
        manifest = {"seeds": ["1", "2", "3"], "factionSets": groups, "tables": tables}
        cases, facts = [], []
        for seed in manifest["seeds"]:
            for group in groups:
                group = sorted(group)
                for rotation in range(len(group)):
                    ordered = group[rotation:] + group[:rotation]
                    for table in names:
                        case_id = str(len(cases))
                        seats = [{"seatIndex": i, "faction": faction, "policyDigest": table}
                                 for i, faction in enumerate(ordered)]
                        cases.append({"caseId": case_id, "runId": "study-run", "seed": seed, "playerCount": len(group),
                                      "state": "SUCCEEDED", "seats": seats})
                        facts.append({"caseId": case_id, "seats": [
                            {"seat": i, "faction": faction,
                             "cardsPlayed": {"NULLIFY/I": 1} if table == "cautious" else {},
                             "specialAccepts": {}}
                            for i, faction in enumerate(ordered)]})
        return manifest, cases, facts

    def test_pairs_complete_seed_blocks_and_resamples_differences(self):
        manifest, cases, facts = self.fixture()
        result = analysis.analyze(manifest, cases, facts)
        cell = result["styles"]["cautious"]["cells"][0]
        self.assertEqual((cell["blocks"], cell["seatGamesPerSide"], cell["effect"]), (3, 117, 1))
        self.assertEqual(cell["interval"], [1, 1])
        self.assertFalse(cell["passes"])  # Three seed blocks are not enough evidence.
        self.assertEqual(result, analysis.analyze(manifest, cases, facts))
        special = result["styles"]["special-heavy"]["cells"][0]
        self.assertEqual(special["status"], "INSUFFICIENT_SAMPLE")
        self.assertEqual(special["reason"], "NO_ACCEPTED_PLAYS")
        self.assertNotIn("interval", special)

    def test_analysis_cli_writes_portable_line_endings(self):
        manifest, cases, facts = self.fixture()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            (root / "experiment.json").write_text(
                json.dumps({"manifest": manifest, "manifestDigest": "digest"}), encoding="utf-8")
            (root / "report.json").write_text(
                json.dumps({"complete": True, "runId": "study-run", "manifestDigest": "digest"}), encoding="utf-8")
            for name, entries in (("cases", cases), ("facts", facts)):
                (root / f"{name}.jsonl").write_text("\n".join(map(json.dumps, entries)) + "\n", encoding="utf-8")
            subprocess.run([sys.executable, str(Path(analysis.__file__)), str(root / "manifest.json"),
                            str(root / "experiment.json"), str(root / "report.json"),
                            str(root / "cases.jsonl"), str(root / "facts.jsonl"), str(root / "result.json")], check=True)
            self.assertNotIn(b"\r\n", (root / "result.json").read_bytes())

    def test_missing_case_excludes_whole_seed_and_duplicate_is_rejected(self):
        manifest, cases, facts = self.fixture()
        missing = next(i for i, case in enumerate(cases)
                       if case["seed"] == "2" and case["seats"][0]["policyDigest"] == "cautious")
        cases.pop(missing)
        result = analysis.analyze(manifest, cases, facts)
        self.assertEqual(result["styles"]["cautious"]["excludedSeeds"], ["2"])
        cases[0]["state"] = "FAILED"
        result = analysis.analyze(manifest, cases, facts)
        self.assertEqual(result["styles"]["cautious"]["excludedSeeds"], ["1", "2"])
        self.assertEqual(result["caseCountsByTable"]["spread"]["nonSucceeded"], 1)
        with self.assertRaisesRegex(ValueError, "Duplicate case"):
            analysis.analyze(manifest, cases + [cases[0]], facts)

    def test_digest_is_order_independent(self):
        self.assertEqual(study.digest({"b": 2, "a": 1}), study.digest({"a": 1, "b": 2}))
        self.assertNotEqual(study.digest({}), study.digest({"a": 1}))

    def test_special_revision_uses_secondary_special_and_accepts_two_tables(self):
        manifest, cases, facts = self.fixture()
        manifest["tables"] = [t for t in manifest["tables"] if t["id"] in ("spread", "special-heavy")]
        candidate = manifest["tables"][1]
        candidate["id"] = "special-heavy-revision"
        cases = [case for case in cases if case["seats"][0]["policyDigest"] in ("spread", "special-heavy")]
        ids = {case["caseId"] for case in cases}
        facts = [fact for fact in facts if fact["caseId"] in ids]
        for fact in facts:
            for seat in fact["seats"]:
                seat["specialAccepts"][analysis.SPECIAL_REVISION[seat["faction"]]] = 2
        result = analysis.analyze(manifest, cases, facts)
        self.assertEqual(result["styles"]["special-heavy-revision"]["cells"][0]["effect"], 0)

    def test_study_merge_rejects_seed_overlap_and_rule_changes(self):
        manifest, _, _ = self.fixture()
        manifest.update({"variants": [1], "services": {}, "contracts": {}, "playerCounts": [3, 4, 5],
                         "seatRotationMode": "CYCLIC", "timingMode": "LOGICAL", "policies": []})
        baseline = manifest["tables"][0]
        baseline["seats"][0].update({"policyId": "faction-spread", "policyVersion": "1.0.0"})
        other = dict(manifest, seeds=["4"], tables=[baseline, {"id": "revision"}])
        def results(source, styles):
            return {"manifestSha256": manifest_sha256(source), "styles": styles}

        first = results(manifest, {"one": {}})
        second = results(other, {"two": {}})
        combined, values = merge.merge(manifest, other, first, second)
        self.assertEqual(combined["tables"][-1]["id"], "revision")
        self.assertEqual(set(values["styles"]), {"one", "two"})
        with self.assertRaisesRegex(ValueError, "measurements do not match"):
            merge.merge(manifest, other, first, results(manifest, {}))
        overlapping = dict(other, seeds=["1"])
        with self.assertRaisesRegex(ValueError, "overlap"):
            merge.merge(manifest, overlapping, first, results(overlapping, {}))
        changed_rules = dict(other, variants=[2])
        with self.assertRaisesRegex(ValueError, "variants"):
            merge.merge(manifest, changed_rules, first, results(changed_rules, {}))
        changed_seat = dict(baseline["seats"][0], policyDigest="other-spread")
        changed_baseline = dict(other, tables=[dict(baseline, seats=[changed_seat]), other["tables"][1]])
        with self.assertRaisesRegex(ValueError, "different spread baselines"):
            merge.merge(manifest, changed_baseline, first, results(changed_baseline, {}))

    def test_publication_rejects_incomplete_studies(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(ValueError, "must complete"):
                publish.publish(root, root, root, root / "rules", root / "config", root / "out")

    def test_mixed_tables_rotate_every_validated_style_through_every_seat(self):
        manifest, _, _ = self.fixture()
        styles = [name for name in (table["id"] for table in manifest["tables"]) if name != "spread"]
        manifest["policies"] = [{"id": "faction-search", "version": "1.0.0", "artifactDigest": name,
                                 "parameters": {}} for name in styles]
        manifest.update({"name": "study", "playerCounts": [3, 4, 5]})
        results = {"styles": {name: {"cells": pooled_passes()} for name in styles}}
        for players, table_manifest in mixed.manifests(manifest, results).items():
            for seat in range(players):
                self.assertCountEqual([table["seats"][seat]["policyDigest"] for table in table_manifest["tables"]], styles)
            self.assertTrue(all(len(group) == players for group in table_manifest["factionSets"]))
        results["styles"][styles[0]]["cells"] = pooled_passes(("REVISIONISTS",))
        five_player = mixed.manifests(manifest, results)[5]
        self.assertNotIn(styles[0], [p["artifactDigest"] for p in five_player["policies"]])
        for seat in range(5):
            self.assertCountEqual([table["seats"][seat]["policyDigest"] for table in five_player["tables"]], styles[1:])
        results["styles"][styles[1]]["cells"] = []
        with self.assertRaisesRegex(ValueError, "Need 3 to 5"):
            mixed.manifests(manifest, results)

    def test_mixed_report_and_exposure_are_checked(self):
        manifest, _, _ = self.fixture()
        styles = [t["id"] for t in manifest["tables"] if t["id"] != "spread"]
        manifest["policies"] = [{"id": "faction-search", "version": "1.0.0", "artifactDigest": name,
                                 "parameters": {}} for name in styles]
        results = {"styles": {name: {"cells": pooled_passes()} for name in styles}}
        manifest = mixed.manifests(manifest, results)[3]
        manifest["seeds"] = ["1"]
        cases, cohorts = [], []
        for table in manifest["tables"]:
            for seat in table["seats"]:
                cohorts.append({"tableId": table["id"], "playerCount": 3, "factionSet": None,
                                "seatIndex": seat["seatIndex"], "policyDigest": seat["policyDigest"]})
            for group in manifest["factionSets"]:
                group = sorted(group)
                for rotation in range(3):
                    factions = group[rotation:] + group[:rotation]
                    cases.append({"runId": "mixed-run", "seed": "1", "playerCount": 3, "state": "SUCCEEDED", "seats": [
                        {**seat, "faction": factions[seat["seatIndex"]]} for seat in table["seats"]]})
        experiment = {"manifest": manifest, "manifestDigest": "digest"}
        report = {"complete": True, "runId": "mixed-run", "manifestDigest": "digest", "cohorts": cohorts}
        self.assertEqual(mixed_check.check(manifest, experiment, report, cases)["exposurePerStyleFactionSeat"], 6)
        with self.assertRaisesRegex(ValueError, "manifest digest mismatch"):
            mixed_check.check(manifest, experiment, dict(report, manifestDigest="other"), cases)
        with self.assertRaisesRegex(ValueError, "manifest digest mismatch"):
            mixed_check.check(dict(manifest, seeds=["2"]), experiment, report, cases)
        with self.assertRaisesRegex(ValueError, "run IDs do not match"):
            mixed_check.check(manifest, experiment, report, [dict(cases[0], runId="other"), *cases[1:]])
        cohorts[0]["policyDigest"] = "incorrect"
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            mixed_check.check(manifest, experiment, report, cases)

    def test_published_presets_are_covered_by_measurements_and_mixed_seats(self):
        root = Path(__file__).parents[1] / "examples" / "play-styles" / "1.0.0"
        population = json.loads((root / "presets.json").read_text(encoding="utf-8"))
        self.assertEqual(population["policyParameterSpaceVersion"], 1)
        presets = population["presets"]
        results = json.loads((root / "initial-measurements.json").read_text(encoding="utf-8"))["styles"]
        revision = root / "revision-measurements.json"
        if revision.exists():
            results.update(json.loads(revision.read_text(encoding="utf-8"))["styles"])
        validation = json.loads((root / "initial-validation.json").read_text(encoding="utf-8"))
        for study_name in ("initial", "revision"):
            measurements = json.loads((root / f"{study_name}-measurements.json").read_text(encoding="utf-8"))
            source = json.loads((root / f"{study_name}-validation.json").read_text(encoding="utf-8"))
            self.assertEqual(measurements["manifestSha256"], manifest_sha256(source))
            self.assertRegex(measurements["source"]["factsSha256"], r"^[0-9a-f]{64}$")
        for cell in results["information-oriented"]["cells"]:
            self.assertLessEqual(cell["baselineDecoyCount"], cell["baselineCount"])
            self.assertLessEqual(cell["candidateDecoyCount"], cell["candidateCount"])
        rules_hash = hashlib.sha256((root / "effective-rules.json").read_bytes()).hexdigest()
        self.assertEqual(rules_hash, validation["variants"][0]["rulesArtifact"]["digest"])
        self.assertGreaterEqual(len(presets), 3)
        for preset in presets:
            policy = preset["policy"]
            self.assertEqual(policy["artifactDigest"], study.digest(policy["parameters"]))
            self.assertFalse(set(policy["parameters"]) & study.UNUSED_SPECIAL_AFFINITIES)
            passed = [cell["faction"] for cell in results[preset["name"]]["cells"]
                      if cell["playerCount"] is None and cell.get("passes")]
            self.assertEqual(preset["factionCoverage"], passed)
            self.assertCountEqual(preset["factionCoverage"], analysis.FACTIONS)
        for players in (3, 4, 5):
            manifest = json.loads((root / f"mixed-{players}.json").read_text(encoding="utf-8"))
            digests = {p["policy"]["artifactDigest"] for p in presets}
            self.assertEqual({p["artifactDigest"] for p in manifest["policies"]}, digests)
            for seat in range(players):
                self.assertEqual({t["seats"][seat]["policyDigest"] for t in manifest["tables"]}, digests)
        evidence = json.loads((root / "mixed-evidence.json").read_text(encoding="utf-8"))
        for name, record in evidence.items():
            exposure = json.loads((root / f"{name}-exposure.json").read_text(encoding="utf-8"))
            self.assertEqual(record["cases"], exposure["cases"])
            self.assertRegex(record["caseRowsSha256"], r"^[0-9a-f]{64}$")

    def test_published_pooled_counts_match_player_count_strata(self):
        root = Path(__file__).parents[1] / "examples" / "play-styles" / "1.0.0"
        paths = [root / "initial-measurements.json"]
        if (root / "revision-measurements.json").exists():
            paths.append(root / "revision-measurements.json")
        for path in paths:
            data = json.loads(path.read_text(encoding="utf-8"))
            for result in data["styles"].values():
                for faction in analysis.FACTIONS:
                    cells = [c for c in result["cells"] if c["faction"] == faction]
                    pooled = next(c for c in cells if c["playerCount"] is None)
                    strata = [c for c in cells if c["playerCount"] is not None]
                    for field in ("seatGamesPerSide", "baselineCount", "candidateCount"):
                        self.assertEqual(pooled[field], sum(c[field] for c in strata))
                    if pooled.get("reason") == "NO_ACCEPTED_PLAYS":
                        self.assertEqual((pooled["baselineCount"], pooled["candidateCount"]), (0, 0))
                        self.assertFalse(pooled["passes"])
                        continue
                    self.assertAlmostEqual(pooled["effect"],
                                           (pooled["candidateCount"] - pooled["baselineCount"]) / pooled["seatGamesPerSide"])


if __name__ == "__main__":
    unittest.main()
