import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import cross_style_baseline as baseline
import same_target_voids as voids


def discovery():
    return {"items": [
        {"id": "faction-spread", "version": "1.0.0", "artifactDigest": "spread-digest"},
        {"id": "faction-greedy", "version": "2.0.0", "artifactDigest": "greedy-digest"},
    ]}


def bundles():
    return {"items": [{"rulesArtifact": {"digest": "rules"}, "contentArtifact": {"digest": "content"},
                       "effectiveRulesDigest": "effective-rules", "effectiveContentDigest": "content",
                       "services": {"game": 1}, "contracts": {"session-api": "1.0.0"}}]}


def presets(*names):
    return {"policyParameterSpaceVersion": 1, "presets": [
        {"name": name, "version": "1.0.0", "factionCoverage": [],
         "policy": {"id": "faction-search", "version": "1.0.0",
                    "artifactDigest": f"{name}-digest", "parameters": {}}}
        for name in names]}


def game(name, commitments, settlements, seats=(0, 1)):
    factions = ("WEAVERS", "PROPHETS", "REVISIONISTS", "ACTIVISTS", "ERASERS")
    return {"game": name, "seats": [{"seat": seat, "faction": factions[seat]} for seat in seats],
            "commitments": commitments, "settlements": settlements}


def commitment(seat, era=1, event="e", outcome="o"):
    return {"seat": seat, "era": era, "event": event, "outcome": outcome}


def settlement(seat, era, reason):
    return {"seat": seat, "era": era, "reason": reason}


class CrossStyleBaselineTest(unittest.TestCase):
    def test_tables_share_seeds_rotations_and_pins(self):
        manifest = baseline.build_manifest(discovery(), bundles(), presets("a", "b", "c"), 3, 9300000, 3)
        self.assertEqual([table["id"] for table in manifest["tables"]],
                         ["spread", "greedy", "mixed-1", "mixed-2", "mixed-3"])
        self.assertEqual(manifest["seeds"], ["9300000", "9300001", "9300002"])
        self.assertEqual(manifest["playerCounts"], [3])
        self.assertEqual(len(manifest["factionSets"]), 10)
        variant, = manifest["variants"]
        self.assertEqual(variant["label"], "p3-recording")
        self.assertEqual(variant["parameterSpaceVersion"], 3)
        self.assertEqual(variant["overrides"], {"game.rules.victory.normal-victory": False,
                                                "game.rules.victory.weaver-chain-length": 3})
        for table in manifest["tables"][:2]:
            self.assertEqual({seat["policyDigest"] for seat in table["seats"]},
                             {manifest["policies"][0 if table["id"] == "spread" else 1]["artifactDigest"]})

    def test_mixed_tables_rotate_every_preset_through_every_seat(self):
        manifest = baseline.build_manifest(discovery(), bundles(), presets("a", "b", "c"), 4, 9300000, 2)
        mixed = [table for table in manifest["tables"] if table["id"].startswith("mixed-")]
        self.assertEqual(len(mixed), 3)
        for seat in range(4):
            self.assertCountEqual([table["seats"][seat]["policyDigest"] for table in mixed],
                                  ["a-digest", "b-digest", "c-digest"])
        self.assertEqual(manifest["seeds"], ["9400000", "9400001"])

    def test_sizes_do_not_share_seeds(self):
        three = baseline.build_manifest(discovery(), bundles(), presets("a", "b", "c"), 3, 9300000, 3)
        four = baseline.build_manifest(discovery(), bundles(), presets("a", "b", "c"), 4, 9300000, 3)
        five = baseline.build_manifest(discovery(), bundles(), presets("a", "b", "c"), 5, 9300000, 3)
        self.assertFalse(set(three["seeds"]) & set(four["seeds"]))
        self.assertFalse(set(three["seeds"]) & set(five["seeds"]))

    def test_invalid_inputs_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            baseline.build_manifest({"items": []}, bundles(), presets("a", "b", "c"), 3, 9300000, 3)
        with self.assertRaisesRegex(ValueError, "lane bundle"):
            baseline.build_manifest(discovery(), {"items": []}, presets("a", "b", "c"), 3, 9300000, 3)
        with self.assertRaisesRegex(ValueError, "3 to 5 presets"):
            baseline.build_manifest(discovery(), bundles(), presets("a", "b"), 3, 9300000, 3)
        with self.assertRaisesRegex(ValueError, "at least 2 blocks"):
            baseline.parse_blocks(["3:1"])
        with self.assertRaisesRegex(ValueError, "3, 4 or 5"):
            baseline.build_manifest(discovery(), bundles(), presets("a", "b", "c"), 6, 9300000, 3)

    def test_cli_writes_one_manifest_per_size(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, data in (("policies", discovery()), ("bundles", bundles()), ("presets", presets("a", "b", "c"))):
                (root / f"{name}.json").write_text(json.dumps(data), encoding="utf-8")
            subprocess.run([sys.executable, str(Path(baseline.__file__)), str(root / "policies.json"),
                            str(root / "bundles.json"), str(root / "presets.json"), str(root / "out"),
                            "--blocks", "3:2", "--blocks", "4:2"], check=True)
            self.assertTrue((root / "out" / "recording-3p.json").is_file())
            self.assertTrue((root / "out" / "recording-4p.json").is_file())
            five = json.loads((root / "out" / "recording-5p.json").read_text(encoding="utf-8"))
            self.assertEqual(len(five["seeds"]), 40)


class SameTargetVoidsTest(unittest.TestCase):
    def test_verifiable_group_without_erasure(self):
        games = [game("g1", [commitment(0), commitment(1)],
                      [settlement(0, 1, "OTHER"), settlement(1, 1, "OTHER")])]
        result = voids.analyze(games)
        self.assertEqual(result["summary"],
                         {"games": 1, "groups": 1, "verifiableGroups": 1,
                          "incompleteGroups": 0, "erasureAttributedGroups": 0})
        group, = result["groups"]
        self.assertEqual((group["game"], group["era"], group["seats"]), ("g1", 1, [0, 1]))
        self.assertTrue(group["verifiable"])
        self.assertFalse(group["erasureAttributed"])

    def test_erasure_attributed_only_with_outcome_in_era(self):
        base = [commitment(0), commitment(1)]
        with_erasure = game("g1", base, [settlement(0, 1, "OTHER"), settlement(1, 1, "ANNIHILATED_OUTCOME")])
        without_records = game("g2", base, [])
        groups = {group["game"]: group for group in voids.analyze([with_erasure, without_records])["groups"]}
        self.assertTrue(groups["g1"]["erasureAttributed"])
        self.assertFalse(groups["g2"]["verifiable"])
        self.assertFalse(groups["g2"]["erasureAttributed"])

    def test_settled_and_single_commitments_form_no_group(self):
        games = [game("g1", [commitment(0, outcome="won"), commitment(1)],
                      [settlement(0, 1, "CHAIN_LINK_ADDED"), settlement(1, 1, "OTHER")]),
                 game("g2", [commitment(0, event="solo")], [settlement(0, 1, "OTHER")])]
        self.assertEqual(voids.analyze(games)["summary"]["groups"], 0)

    def test_cli_round_trip(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            games = [game("g1", [commitment(0), commitment(1)],
                          [settlement(0, 1, "OTHER"), settlement(1, 1, "OTHER")])]
            (root / "games.jsonl").write_text("\n".join(map(json.dumps, games)) + "\n", encoding="utf-8")
            subprocess.run([sys.executable, str(Path(voids.__file__)), str(root / "games.jsonl"),
                            str(root / "out.json")], check=True)
            result = json.loads((root / "out.json").read_text(encoding="utf-8"))
            self.assertEqual(result["summary"]["verifiableGroups"], 1)
            self.assertNotIn(b"\r\n", (root / "out.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
