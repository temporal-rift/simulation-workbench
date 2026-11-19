# Fidelity edge variants

The fidelity check compares the simulator with a real lane on the baseline and two variants at opposite edges of the
parameter space. `src/main/resources/fidelity/edge-variants.json` ships the same sets as this record, and a check
records the space revision it ran on. Both sets override every parameter of the space: `low` at the range minimum and
`high` at the range maximum, except where a value is held off the edge, which † marks (a penalty whose weakest value
is the "low" edge, or a value a constraint of the space forbids at the edge). Boolean parameters take `false` for `low`
and `true` for `high`.

The three new settings are the cost of a missed Thread (`CHAIN_LINK_MISSED`), the Eraser objective at five players and
whether the Activist's successful eras must be consecutive at five players. `low` sets the Thread miss to -2, the
five-player Eraser objective to 3 and the Activist's eras as not consecutive; `high` sets the miss to 0 (no penalty),
the Eraser objective to 5 and the Activist's eras as consecutive. The Thread miss is a penalty, so its "low" edge is
the strongest penalty and its "high" edge the weakest, the same way as the other penalties of the space. None of the
three is tied to a constraint, so none is held off the edge. A check on this space is refused with the reason
`UNKNOWN_BASELINE` when the simulator's baseline rules lack one of the three keys, which is the case until the services
that serve them are deployed to the simulator.

The carried-over the earlier set settings keep their edges (three or five events per era, the three switches off or on, the
Detonate cost and wasted-erasure value at 0 or at their strongest penalty, and no final-era discount or two steps).
The Weaver chain baseline is four links and `SECRET_OUTCOME_WON` is 4; both stay inside their ranges, so their edges are
the range minimum (`low`) and maximum (`high`).

## Overrides

A value marked † is not the range minimum (`low`) or maximum (`high`); the rule above and the paragraph above give the
reason. The `suppress-shift` keys and `EXPOSE_CHANGED_PLAYER_BEHAVIOR` are derived from `push-shift` and
`EXPOSE_SIGNATURE_REVEALED` and follow them.

| Key | Baseline | `low` | `high` |
|---|---|---|---|
| `game.rules.win-score-threshold` | 20 | 16 | 26 |
| `game.rules.victory.eraser-annihilations` | 4 | 3 | 5 |
| `game.rules.victory.prophet-written-resolutions` | 3 | 2 | 4 |
| `game.rules.victory.revisionist-successful-eras` | 3 | 2 | 4 |
| `game.rules.victory.weaver-chain-length` | 4 | 3 | 4 |
| `game.rules.victory.activist-consecutive-declarations` | 3 | 2 | 3 † |
| `game.rules.stabilization.prophet-written-resolutions` | 3 | 2 | 4 |
| `game.rules.stabilization.weaver-active-chain-links` | 2 | 1 | 3 |
| `game.rules.max-cascaded-paradoxes` | 3 | 3 | 4 |
| `game.rules.cards-per-hand` | 5 | 4 | 6 |
| `game.rules.cards-per-deal` | 7 | 5 | 9 |
| `game.rules.seal-max-uses-per-game` | 2 | 1 | 3 |
| `game.rules.card-category-weights.PROBABILITY_SHIFTER` | 35 | 20 | 50 |
| `game.rules.card-category-weights.INFORMATION` | 25 | 10 | 10 † |
| `game.rules.card-category-weights.DISRUPTION` | 25 | 40 † | 10 † |
| `game.rules.card-category-weights.PARADOX` | 15 | 30 † | 30 |
| `game.rules.card-grade-weights.I` | 60 | 80 † | 80 |
| `game.rules.card-grade-weights.II` | 30 | 15 | 15 † |
| `game.rules.card-grade-weights.III` | 10 | 5 | 5 † |
| `game.rules.probability.push-shift.I` | 10 | 5 | 15 |
| `game.rules.probability.push-shift.II` | 20 | 15 | 25 |
| `game.rules.probability.push-shift.III` | 30 | 25 | 40 |
| `game.rules.probability.swing-shift.I` | 15 | 10 | 20 |
| `game.rules.probability.swing-shift.II` | 30 | 25 | 35 |
| `game.rules.probability.swing-shift.III` | 45 | 40 | 50 |
| `game.rules.probability.amplify-multiplier.I` | 1.5 | 1.25 | 1.75 |
| `game.rules.probability.amplify-multiplier.II` | 2 | 1.75 | 2.5 |
| `game.rules.probability.amplify-multiplier.III` | 3 | 2.5 | 3.5 |
| `game.rules.probability.momentum-bonus` | 10 | 5 | 15 |
| `game.rules.probability.rally-multiplier` | 1.5 | 1.25 | 2 |
| `game.rules.probability.thread-shift` | 10 | 5 | 15 |
| `game.rules.scoring.score-deltas.ANNIHILATED_OUTCOME` | 3 | 2 | 4 |
| `game.rules.scoring.score-deltas.CORRUPTED_OPPONENT_CARD` | 2 | 1 | 3 |
| `game.rules.scoring.score-deltas.EVENT_RESOLVED_AS_WRITTEN` | 4 | 3 | 5 |
| `game.rules.scoring.score-deltas.FULFILLMENT_SUCCEEDED` | 8 | 6 | 10 |
| `game.rules.scoring.score-deltas.EVENT_RESOLVED_DIFFERENTLY_THAN_WRITTEN` | -2 | -4 | -1 |
| `game.rules.scoring.score-deltas.SECRET_OUTCOME_WON` | 4 | 3 | 5 |
| `game.rules.scoring.score-deltas.MIMIC_NEVER_TRACED` | 4 | 4 | 8 |
| `game.rules.scoring.score-deltas.MIMIC_CONTRIBUTED_TO_WIN` | 1 | 1 | 3 |
| `game.rules.scoring.score-deltas.CHAIN_LINK_ADDED` | 2 | 1 | 3 |
| `game.rules.scoring.score-deltas.CHAIN_COMPLETED` | 10 | 8 | 12 |
| `game.rules.scoring.score-deltas.CHAIN_BROKEN` | -3 | -5 | -1 |
| `game.rules.scoring.score-deltas.DECLARED_OUTCOME_WON_WITH_RALLY` | 6 | 5 | 8 |
| `game.rules.scoring.score-deltas.DECLARED_OUTCOME_WON` | 5 | 4 | 6 |
| `game.rules.scoring.score-deltas.EXPOSE_SIGNATURE_REVEALED` | 2 | 1 | 3 |
| `game.rules.scoring.score-deltas.PARADOX_CASCADE_PENALTY` | -2 | -4 | -1 |
| `game.rules.annihilate-earliest-round` | 2 | 1 | 3 |
| `game.rules.scoring.written-outcome-shields-annihilation` | true | false | true |
| `game.rules.probability.erased-weight-to-written-outcome` | true | false | true |
| `game.rules.rewrite-latest-round` | 2 | 1 | 3 |
| `game.rules.scoring.score-deltas.SECRET_OUTCOME_MISSED` | -1 | -3 | 0 |
| `game.rules.scoring.expose-requires-declared-event` | true | false | true |
| `game.rules.scoring.annihilation-requires-consequence` | true | false | true |
| `game.rules.foresight.writings-per-era` | 1 | 1 | 3 |
| `game.rules.stabilization.faction-claims` | false | false | true |
| `game.rules.tapestry-absorbs-chain-conflict` | false | false | true |
| `game.rules.paradox.fate-contradiction` | true | false | true |
| `game.rules.scoring.score-deltas.DECLARED_OUTCOME_MISSED_WITH_RALLY` | -2 | -4 | 0 |
| `game.rules.scoring.score-deltas.DECLARED_OUTCOME_MISSED` | -1 | -3 | 0 |
| `game.rules.scoring.score-deltas.PARADOX_STABILIZED` | 0 | -3 | 0 |
| `game.rules.events-per-era.3` | 3 | 3 | 5 |
| `game.rules.events-per-era.4` | 4 | 3 | 5 |
| `game.rules.events-per-era.5` | 5 | 3 | 5 |
| `game.rules.scoring.erased-outcome-voids-bets` | true | false | true |
| `game.rules.paradox.detonate-contests-stabilize` | true | false | true |
| `game.rules.scoring.score-deltas.PARADOX_DETONATED` | -1 | 0 † | -3 † |
| `game.rules.scoring.score-deltas.ANNIHILATION_WASTED` | 0 | 0 † | -2 † |
| `game.rules.victory.score-tiebreak` | true | false | true |
| `game.rules.victory.final-era-objective-discount` | 1 | 0 | 2 |
| `game.rules.scoring.score-deltas.CHAIN_LINK_MISSED` | -1 | -2 | 0 |
| `game.rules.victory.eraser-annihilations-by-player-count.5` | 3 | 3 | 5 |
| `game.rules.victory.activist-consecutive-eras-by-player-count.5` | false | false | true |

## Validation

`FidelityEdgeVariantsTest` validates both sets of each version through the workbench's variant rules against that
version's parameter space, and checks that every key and value of each resource appears in the table of its record and
that the table lists nothing else.
