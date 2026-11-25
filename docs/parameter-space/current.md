# Parameter space

This is the one parameter space the workbench serves (`parameter-spaces/parameter-space.json`; its `version` is the
revision of its content, now 2). It was reached through successive versions whose decision records are kept unchanged in
[docs/history](../history) (`parameter-space-v1.md` to `parameter-space-v5.md`); version 1's record holds the rationale
of every original parameter, range and constraint. The rebalance settings below are the newest additions, and "Revision 2" lists what the faction-bet window changed.

## Newest parameters

| Key | Type | Services | Baseline | Range | Step | Rationale |
|---|---|---|---|---|---|---|
| `game.rules.scoring.score-deltas.CHAIN_LINK_MISSED` | NUMBER | game-service | -1 | [-2, 0] | 1 | What a Weaver pays when a Thread's event resolves to another winner; 0 is off |
| `game.rules.victory.eraser-annihilations-by-player-count.5` | NUMBER | game-service | 4 | [3, 5] | 1 | Annihilations an Eraser needs at five players (4 equals `eraser-annihilations`: no five-player exception); the other table sizes use `eraser-annihilations` |
| `game.rules.victory.prophet-written-resolutions-by-player-count.5` | NUMBER | game-service | 3 | [2, 4] | 1 | Written resolutions a Prophet needs at five players; the other table sizes use `prophet-written-resolutions` |
| `game.rules.victory.revisionist-successful-eras-by-player-count.5` | NUMBER | game-service | 3 | [2, 4] | 1 | Winning eras a Revisionist needs at five players; the other table sizes use `revisionist-successful-eras` |
| `game.rules.victory.activist-consecutive-eras-by-player-count.5` | BOOLEAN | game-service | `true` | `false`, `true` | | Whether an Activist's successful eras must be consecutive at five players; `true` is no five-player exception, `false` counts them in total |

## Baselines moved by the rebalance

- `game.rules.victory.weaver-chain-length`: baseline 3 → 4 (range [3, 4] unchanged).
- `game.rules.scoring.score-deltas.SECRET_OUTCOME_WON`: baseline 3 → 4 (range [3, 5] unchanged).
- Constraints are unchanged and hold at the new baselines: the Weaver chain at 4 × 2 + 10 = 18 < 20 and the Revisionist
  objective at 3 × 4 = 12 < 20.
- The five-player Revisionist and Prophet objectives are separate parameters
  (`game.rules.victory.revisionist-successful-eras-by-player-count.5` and
  `game.rules.victory.prophet-written-resolutions-by-player-count.5`), so a campaign can set them for five players alone.
  The other table sizes use the general parameters, `game.rules.victory.revisionist-successful-eras` (which reaches 2)
  and `game.rules.victory.prophet-written-resolutions`.

## Reading results

Compare the package (every new baseline) with one variant per change switched back to its earlier behavior, rather
than reading absolute win shares: the Weaver chain at 3, `SECRET_OUTCOME_WON` at 3, `CHAIN_LINK_MISSED` at 0, the
five-player Eraser objective at 3 and the five-player Activist streak as not consecutive (`false`), which were the
baselines before revision 2. The `bet_success_rate` of this
space counts every settled Thread, so a Weaver's miss shows in it.

## Revision 2

Revision 2 replaces `game.rules.rewrite-latest-round` with `game.rules.faction-bet-latest-round` (NUMBER, game-service and
timeline-service, baseline 2, range [1, 3], step 1: the one window for every faction bet) and lets
`game.rules.probability.thread-shift` reach 0 (range [0, 15], baseline 0, step 5; 0 is a Thread without push). Two more
baselines mean "no exception": `game.rules.victory.final-era-objective-discount` is 0 (range [0, 2] unchanged) and the
five-player settings above are 4 and `true`. `game.rules.victory.weaver-chain-length` keeps [3, 4] and baseline 4. A
variant that overrides `game.rules.rewrite-latest-round`, or sets `thread-shift` below 0, is refused naming the key.
Experiments and campaigns stored on revision 1 can no longer be replayed; their results stay as files.
