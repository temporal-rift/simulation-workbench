# Parameter space version 5

Version 5 is version 4 ([v4.md](parameter-space-v4.md)) plus the balance-package rule settings that the game now serves, and the two
baselines those rules moved. Versions 1 to 4 stay served exactly as before, so existing experiments, campaigns and
fidelity checks replay as they ran. The configuration is `parameter-spaces/v5.json`.

## Added parameters

| Key | Type | Services | Baseline | Range | Step | Rationale |
|---|---|---|---|---|---|---|
| `game.rules.events-per-era.3` | NUMBER | game-service | 3 | [3, 5] | 1 | Events an era holds at three players |
| `game.rules.events-per-era.4` | NUMBER | game-service | 4 | [3, 5] | 1 | Events an era holds at four players; 3 is the old fixed count |
| `game.rules.events-per-era.5` | NUMBER | game-service | 5 | [3, 5] | 1 | Events an era holds at five players; 3 is the old fixed count |
| `game.rules.scoring.erased-outcome-voids-bets` | BOOLEAN | game-service | `true` | `false`, `true` | | Whether an Activist declaration or Revisionist Rewrite on an outcome annihilated that era is void (`true`) or scores its miss (`false`) |
| `game.rules.paradox.detonate-contests-stabilize` | BOOLEAN | game-service, timeline-service | `true` | `false`, `true` | | Whether Detonate outnumbering Stabilize lets a paradox cascade and costs each detonator `PARADOX_DETONATED` (`true`), or one Stabilize clears and Detonate doubles the others' penalty (`false`) |
| `game.rules.scoring.score-deltas.PARADOX_DETONATED` | NUMBER | game-service | -1 | [-3, 0] | 1 | What a detonator pays on top of the cascade penalty while the contest is on |
| `game.rules.scoring.score-deltas.ANNIHILATION_WASTED` | NUMBER | game-service | 0 | [-2, 0] | 1 | The reserve dial: what a leading erasure that changed no winner costs the Eraser; 0 is off |
| `game.rules.victory.score-tiebreak` | BOOLEAN | game-service | `true` | `false`, `true` | | Whether simultaneous winners are separated by score, then objective progress (`true`), or share (`false`) |
| `game.rules.victory.final-era-objective-discount` | NUMBER | game-service | 1 | [0, 2] | 1 | Steps fewer every faction objective needs in the final era for a player who advances it there; 0 is off |

## Changed from version 4

- `game.rules.events-per-era` is no longer held fixed: the single count became the three keyed parameters above.
- `game.rules.victory.weaver-chain-length`: baseline 4 → 3 (range [3, 4] unchanged); its services now name both
  services that read it.
- `game.rules.scoring.score-deltas.PARADOX_STABILIZED`: baseline -1 → 0 (range unchanged). Clearing a paradox is free;
  the contest makes the cascade a decision instead.
- `game.rules.scoring.score-deltas.SECRET_OUTCOME_WON` 4 → 3, `MIMIC_NEVER_TRACED` 6 → 4 and
  `MIMIC_CONTRIBUTED_TO_WIN` 2 → 1: the Revisionist values the services serve after the lowering that preceded this
  space. Their ranges are unchanged, so each baseline now sits at its range minimum.
- The `services` list of `game.rules.victory.weaver-chain-length` is corrected here only. Versions 1 to 4 keep the list
  they were recorded with (game-service alone); it has no effect on how a variant replays.
- No constraint changes: at most 5 events × 5 eras = 25 of the 30 deck cards, and C-04 holds at the new chain
  baseline (3 × 2 + 10 = 16 < 20).

## Reading results

Compare the package (every new baseline) with one variant per change switched back to its version-4 behavior, rather
than reading absolute win shares. The contest only reads true with the `faction-greedy-v3` bot (criterion 4): the v2
bot chooses Stabilize and Detonate by fixed affinity, so a v2 table makes the contest a property of its affinity table.
