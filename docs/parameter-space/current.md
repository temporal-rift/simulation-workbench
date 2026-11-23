# Parameter space

This is the one parameter space the workbench serves (`parameter-spaces/parameter-space.json`; its `version` is the
revision of its content). It was reached through successive versions whose decision records are kept unchanged in
[docs/history](../history) (`parameter-space-v1.md` to `parameter-space-v5.md`); version 1's record holds the rationale
of every original parameter, range and constraint. The rebalance settings below are the newest additions.

## Newest parameters

| Key | Type | Services | Baseline | Range | Step | Rationale |
|---|---|---|---|---|---|---|
| `game.rules.scoring.score-deltas.CHAIN_LINK_MISSED` | NUMBER | game-service | -1 | [-2, 0] | 1 | What a Weaver pays when a Thread's event resolves to another winner; 0 is off |
| `game.rules.victory.eraser-annihilations-by-player-count.5` | NUMBER | game-service | 3 | [3, 5] | 1 | Annihilations an Eraser needs at five players; the other table sizes use `eraser-annihilations` |
| `game.rules.victory.prophet-written-resolutions-by-player-count.5` | NUMBER | game-service | 3 | [2, 4] | 1 | Written resolutions a Prophet needs at five players; the other table sizes use `prophet-written-resolutions` |
| `game.rules.victory.revisionist-successful-eras-by-player-count.5` | NUMBER | game-service | 3 | [2, 4] | 1 | Winning eras a Revisionist needs at five players; the other table sizes use `revisionist-successful-eras` |
| `game.rules.victory.activist-consecutive-eras-by-player-count.5` | BOOLEAN | game-service | `false` | `false`, `true` | | Whether an Activist's successful eras must be consecutive at five players; `false` counts them in total |

## Baselines moved by the rebalance

- `game.rules.victory.weaver-chain-length`: baseline 3 → 4 (range [3, 4] unchanged).
- `game.rules.scoring.score-deltas.SECRET_OUTCOME_WON`: baseline 3 → 4 (range [3, 5] unchanged).
- Constraints are unchanged and hold at the new baselines: the Weaver chain at 4 × 2 + 10 = 18 < 20 and the Revisionist
  objective at 3 × 4 = 12 < 20.
- The Revisionist objective by table size (two winning eras at four and five players) is not a separate parameter:
  `game.rules.victory.revisionist-successful-eras` already reaches 2, so a campaign explores it for every table size.

## Reading results

Compare the package (every new baseline) with one variant per change switched back to its earlier behavior, rather
than reading absolute win shares: the Weaver chain at 3, `SECRET_OUTCOME_WON` at 3, `CHAIN_LINK_MISSED` at 0, the
five-player Eraser objective at 4 and the five-player Activist streak as consecutive. The `bet_success_rate` of this
space counts every settled Thread, so a Weaver's miss shows in it.
