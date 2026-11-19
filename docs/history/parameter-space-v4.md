# Parameter space version 4

Version 4 is version 3 ([v3.md](parameter-space-v3.md)) plus the faction-balance rule switches that the game now serves, and the two
baselines those rules moved. Versions 1 to 3 stay served exactly as before, so existing experiments, campaigns and
fidelity checks replay as they ran. The configuration is `parameter-spaces/v4.json`.

## Added parameters

| Key | Type | Services | Baseline | Range | Step | Rationale |
|---|---|---|---|---|---|---|
| `game.rules.foresight.writings-per-era` | NUMBER | game-service, timeline-service | 1 | [1, 3] | 1 | How many outcomes a Prophet may write in an era. Beyond it a Foresight still reveals the next era's events but writes nothing. 3 is the old behavior (one per round) |
| `game.rules.stabilization.faction-claims` | BOOLEAN | game-service | `false` | `false`, `true` | | Whether five eras without a winner give the Prophet and the Weaver their claims (`true`) or go to the highest score (`false`) |
| `game.rules.tapestry-absorbs-chain-conflict` | BOOLEAN | timeline-service | `false` | `false`, `true` | | Whether Tapestry absorbs an Annihilate on the pending link (`true`, no paradox) or the paradox is raised and Tapestry only keeps the chain from breaking (`false`) |
| `game.rules.paradox.fate-contradiction` | BOOLEAN | timeline-service | `true` | `false`, `true` | | Whether annihilating a Prophet's written outcome raises a `FATE_CONTRADICTION` paradox |
| `game.rules.scoring.score-deltas.DECLARED_OUTCOME_MISSED_WITH_RALLY` | NUMBER | game-service | -2 | [-4, 0] | 1 | The score of a Rally declaration whose outcome does not win; 0 turns the penalty off |
| `game.rules.scoring.score-deltas.DECLARED_OUTCOME_MISSED` | NUMBER | game-service | -1 | [-3, 0] | 1 | The score of a Momentum declaration whose outcome does not win; 0 turns the penalty off |
| `game.rules.scoring.score-deltas.PARADOX_STABILIZED` | NUMBER | game-service | -1 | [-3, 0] | 1 | What playing Stabilize costs its player when the event does not cascade; 0 makes it free as before |

## Changed from version 3

- `game.rules.victory.prophet-written-resolutions`: baseline 4 → 3, range [3, 5] → [2, 4]. With one writing per era the
  Prophet's objective is 3.
- `game.rules.victory.weaver-chain-length`: baseline 3 → 4 (range [3, 4] unchanged). The chain now completes at the
  configured length.
- Constraints C-06 and C-07 (a stabilization threshold below its objective) are dropped. The thresholds apply only when
  `game.rules.stabilization.faction-claims` is `true`, and the constraint notation cannot make a constraint conditional.
  The served stabilization thresholds (3 and 2) are therefore not bound to the objectives; they matter only when the
  claims are switched on.
- Constraints C-02 (3 × 4 = 12 < 20) and C-04 (4 × 2 + 10 = 18 < 20) hold at the new baselines.

## Reading results

Compare each rule switched back to its old value, alone and combined, against the baseline, rather than reading
absolute win shares. The `faction-greedy-v2` bot plays one Foresight per era whatever `writings-per-era` says, so a
variant with a value above 1 measures the rule with a bot that does not use the extra writings.
