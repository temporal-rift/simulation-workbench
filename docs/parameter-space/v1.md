# Parameter space version 1

Status: proposed by design for the configuration search (temporal-rift/infrastructure#124, decided in
temporal-rift/simulation-workbench#46). It becomes approved when the design owner approves the pull request that adds
it; any amendment is made in that review or in a later version, never by editing an approved version.

The search generates candidates only as overrides of the parameters below on the baseline rules bundle. The workbench
applies the overrides and validates them against this record (temporal-rift/infrastructure#124, decision A-02). A
candidate that sets a parameter outside its range or step, or breaks a constraint, is never played.

Engineering filled `key`, `services`, `baseline` and `status` from the served configuration and the code that binds
it. Design filled `range`, `step`, the constraints and the inclusion decisions.

## Terms

- **Baseline**: the value in the shared config repository of temporal-rift/infrastructure
  (`config-server/config-repo`), which every lane serves unless a bundle overrides it.
- **Status**: `CONFIGURATION` when a served rules property binds the value, `NOT_CONFIGURATION` when code fixes it.
  Every parameter below is `CONFIGURATION`. The victory thresholds and the Seal limit are served explicitly as of
  temporal-rift/infrastructure#128; before that they existed only as code defaults.
- **Range**: closed interval `[min, max]`. **Step**: the grid the search samples on, starting from `min`; the
  baseline always lies on the grid.
- **Per player count**: `yes` when the value differs by player count. No parameter in version 1 does (see "Held
  fixed").

## Parameters

### Victory

| Key | Services | Baseline | Status | Per player count | Range | Step | Rationale |
|---|---|---|---|---|---|---|---|
| `game.rules.win-score-threshold` | game-service | 20 | CONFIGURATION | no | [16, 26] | 1 | The main pacing lever for normal victory and so for game length (criterion L-02, G-04); wide enough to move the typical ending by about an era either way |
| `game.rules.victory.eraser-annihilations` | game-service | 4 | CONFIGURATION | no | [3, 5] | 1 | Eraser's objective route; one step either way changes its pace by about an era |
| `game.rules.victory.prophet-written-resolutions` | game-service | 4 | CONFIGURATION | no | [3, 5] | 1 | Prophet's objective route, same reasoning |
| `game.rules.victory.revisionist-successful-eras` | game-service | 3 | CONFIGURATION | no | [2, 4] | 1 | Revisionist's objective route; 4 is the most a five-era game can leave room for |
| `game.rules.victory.weaver-chain-length` | game-service | 3 | CONFIGURATION | no | [3, 4] | 1 | Weaver's objective route; a 2-link chain would equal the stabilization claim and make the objective trivial |
| `game.rules.victory.activist-consecutive-declarations` | game-service | 3 | CONFIGURATION | no | [2, 4] | 1 | Activist's objective route; the streak is the faction's identity, so it never drops to 1 |

### Special endings

| Key | Services | Baseline | Status | Per player count | Range | Step | Rationale |
|---|---|---|---|---|---|---|---|
| `game.rules.stabilization.prophet-written-resolutions` | game-service | 3 | CONFIGURATION | no | [2, 4] | 1 | Prophet's claim when the timeline stabilizes (L-03) |
| `game.rules.stabilization.weaver-active-chain-links` | game-service | 2 | CONFIGURATION | no | [1, 3] | 1 | Weaver's claim when the timeline stabilizes (L-03) |
| `game.rules.max-cascaded-paradoxes` | game-service | 3 | CONFIGURATION | no | [3, 4] | 1 | Collapse threshold (L-04); below 3 collapse would become a routine ending, contrary to its role as a rare threat |

### Hand and deal

| Key | Services | Baseline | Status | Per player count | Range | Step | Rationale |
|---|---|---|---|---|---|---|---|
| `game.rules.cards-per-hand` | game-service | 5 | CONFIGURATION | no | [4, 6] | 1 | Whether five cards per era are enough is an open design question |
| `game.rules.cards-per-deal` | game-service | 7 | CONFIGURATION | no | [5, 9] | 1 | The keep choice ("deal 7, keep 5") is a decision only while the deal exceeds the hand |
| `game.rules.seal-max-uses-per-game` | game-service | 2 | CONFIGURATION | no | [1, 3] | 1 | Seal's game-wide budget, the only special with one |
| `game.rules.card-category-weights.PROBABILITY_SHIFTER` | game-service | 35 | CONFIGURATION | no | [20, 50] | 5 | Category weights are a declared tuning dial |
| `game.rules.card-category-weights.INFORMATION` | game-service | 25 | CONFIGURATION | no | [10, 40] | 5 | Same |
| `game.rules.card-category-weights.DISRUPTION` | game-service | 25 | CONFIGURATION | no | [10, 40] | 5 | Same |
| `game.rules.card-category-weights.PARADOX` | game-service | 15 | CONFIGURATION | no | [5, 30] | 5 | Same; never 0, so Collide stays in the game |
| `game.rules.card-grade-weights.I` | game-service | 60 | CONFIGURATION | no | [40, 80] | 5 | Grade rarity is declared configuration |
| `game.rules.card-grade-weights.II` | game-service | 30 | CONFIGURATION | no | [15, 45] | 5 | Same |
| `game.rules.card-grade-weights.III` | game-service | 10 | CONFIGURATION | no | [5, 20] | 5 | Same; never 0, so the top grade stays a decision |

### Probability effects

| Key | Services | Baseline | Status | Per player count | Range | Step | Rationale |
|---|---|---|---|---|---|---|---|
| `game.rules.probability.push-shift.I` | timeline-service | 10 | CONFIGURATION | no | [5, 15] | 5 | Grade magnitudes are declared configuration; adjacent grades may share a range endpoint, and C-10 forbids equal grades |
| `game.rules.probability.push-shift.II` | timeline-service | 20 | CONFIGURATION | no | [15, 25] | 5 | Same |
| `game.rules.probability.push-shift.III` | timeline-service | 30 | CONFIGURATION | no | [25, 40] | 5 | Same |
| `game.rules.probability.swing-shift.I` | timeline-service | 15 | CONFIGURATION | no | [10, 20] | 5 | Same |
| `game.rules.probability.swing-shift.II` | timeline-service | 30 | CONFIGURATION | no | [25, 35] | 5 | Same |
| `game.rules.probability.swing-shift.III` | timeline-service | 45 | CONFIGURATION | no | [40, 50] | 5 | Same |
| `game.rules.probability.amplify-multiplier.I` | timeline-service | 1.5 | CONFIGURATION | no | [1.25, 1.75] | 0.25 | Same |
| `game.rules.probability.amplify-multiplier.II` | timeline-service | 2.0 | CONFIGURATION | no | [1.75, 2.5] | 0.25 | Same |
| `game.rules.probability.amplify-multiplier.III` | timeline-service | 3.0 | CONFIGURATION | no | [2.5, 3.5] | 0.25 | Same |
| `game.rules.probability.momentum-bonus` | timeline-service | 10 | CONFIGURATION | no | [5, 15] | 1 | Momentum's opening bonus; its balance against Rally is an open design question |
| `game.rules.probability.rally-multiplier` | timeline-service | 1.5 | CONFIGURATION | no | [1.25, 2.0] | 0.25 | Rally's boost of inward Round 1 transfers, the other side of that balance |
| `game.rules.probability.thread-shift` | timeline-service | 10 | CONFIGURATION | no | [5, 15] | 5 | The shift the Weaver's Thread applies to its anchored outcome |

`game.rules.probability.suppress-shift.{I, II, III}` (timeline-service, baseline −10, −20, −30) is not searched
independently. It is tied to Push (constraint C-09), so Suppress stays Push's mirror.

### Scoring

All under `game.rules.scoring.score-deltas`, read by game-service. Gains stay positive and penalties stay negative.

| Reason | Baseline | Status | Per player count | Range | Step | Rationale |
|---|---|---|---|---|---|---|
| `ANNIHILATED_OUTCOME` | 3 | CONFIGURATION | no | [2, 4] | 1 | Eraser's objective entry |
| `CORRUPTED_OPPONENT_CARD` | 2 | CONFIGURATION | no | [1, 3] | 1 | Eraser's threshold route |
| `EVENT_RESOLVED_AS_WRITTEN` | 4 | CONFIGURATION | no | [3, 5] | 1 | Prophet's objective entry |
| `FULFILLMENT_SUCCEEDED` | 8 | CONFIGURATION | no | [6, 10] | 1 | Prophet's threshold route |
| `EVENT_RESOLVED_DIFFERENTLY_THAN_WRITTEN` | −2 | CONFIGURATION | no | [−4, −1] | 1 | Prophet's risk |
| `SECRET_OUTCOME_WON` | 4 | CONFIGURATION | no | [3, 5] | 1 | Revisionist's objective entry |
| `MIMIC_NEVER_TRACED` | 6 | CONFIGURATION | no | [4, 8] | 1 | Revisionist's era-5 bonus |
| `MIMIC_CONTRIBUTED_TO_WIN` | 2 | CONFIGURATION | no | [1, 3] | 1 | Revisionist's threshold route |
| `CHAIN_LINK_ADDED` | 2 | CONFIGURATION | no | [1, 3] | 1 | Weaver's objective entry |
| `CHAIN_COMPLETED` | 10 | CONFIGURATION | no | [8, 12] | 1 | Weaver's objective entry |
| `CHAIN_BROKEN` | −3 | CONFIGURATION | no | [−5, −1] | 1 | Weaver's risk |
| `DECLARED_OUTCOME_WON_WITH_RALLY` | 6 | CONFIGURATION | no | [5, 8] | 1 | Activist's Rally payoff |
| `DECLARED_OUTCOME_WON` | 5 | CONFIGURATION | no | [4, 6] | 1 | Activist's Momentum payoff |
| `EXPOSE_SIGNATURE_REVEALED` | 2 | CONFIGURATION | no | [1, 3] | 1 | Activist's threshold route; `EXPOSE_CHANGED_PLAYER_BEHAVIOR` is its alias and takes the same value |
| `PARADOX_CASCADE_PENALTY` | −2 | CONFIGURATION | no | [−4, −1] | 1 | Every player's stake in collapse |

## Constraints

`S(reason)` is `game.rules.scoring.score-deltas.<reason>`, `V(x)` is `game.rules.victory.<x>`, and `T` is
`game.rules.win-score-threshold`. Every constraint holds on the baseline.

| Id | Constraint | Source | Baseline check |
|---|---|---|---|
| C-01 | `V(eraser-annihilations) × S(ANNIHILATED_OUTCOME) < T` | Design: each objective is met while its own entries total below the threshold, so it is a distinct route to victory | 4 × 3 = 12 < 20 |
| C-02 | `V(prophet-written-resolutions) × S(EVENT_RESOLVED_AS_WRITTEN) < T` | Same | 4 × 4 = 16 < 20 |
| C-03 | `V(revisionist-successful-eras) × S(SECRET_OUTCOME_WON) < T` | Same | 3 × 4 = 12 < 20 |
| C-04 | `V(weaver-chain-length) × S(CHAIN_LINK_ADDED) + S(CHAIN_COMPLETED) < T` | Same | 3 × 2 + 10 = 16 < 20 |
| C-05 | `V(activist-consecutive-declarations) × max(S(DECLARED_OUTCOME_WON_WITH_RALLY), S(DECLARED_OUTCOME_WON)) < T` | Same, at the Activist's highest-scoring streak | 3 × 6 = 18 < 20 |
| C-06 | `game.rules.stabilization.prophet-written-resolutions < V(prophet-written-resolutions)` | Design: a stabilization claim sits below the matching objective, because the objective already ends the game | 3 < 4 |
| C-07 | `game.rules.stabilization.weaver-active-chain-links < V(weaver-chain-length)` | Same | 2 < 3 |
| C-08 | `cards-per-hand < cards-per-deal ≤ cards-per-hand + 3` | Design: the keep choice exists only while the deal exceeds the hand; code requires `cards-per-deal ≥ cards-per-hand` | 5 < 7 ≤ 8 |
| C-09 | `suppress-shift.g = −push-shift.g` for each grade `g` | Design: Suppress mirrors Push; code requires Suppress negative | −10, −20, −30 |
| C-10 | `push-shift.I < push-shift.II < push-shift.III`, and the same for `swing-shift` and `amplify-multiplier` | Design: grade II is the baseline and grades I and III bracket it | 10 < 20 < 30; 15 < 30 < 45; 1.5 < 2.0 < 3.0 |
| C-11 | The four `card-category-weights` sum to 100 | The dealer normalises weights, so only their ratios matter; fixing the sum avoids playing one configuration under several names | 35 + 25 + 25 + 15 = 100 |
| C-12 | The three `card-grade-weights` sum to 100, and `I > II > III` | Same normalisation; design: scarcity at the top end | 60 + 30 + 10 = 100; 60 > 30 > 10 |
| C-13 | `S(DECLARED_OUTCOME_WON_WITH_RALLY) > S(DECLARED_OUTCOME_WON)` | Design: Rally pays more for its public risk; Momentum compensates with its bonus | 6 > 5 |
| C-14 | Every value satisfies the binding code's validation (amplify and Rally multipliers above 1.0, Push and Swing positive, Suppress negative, `cards-per-deal ≥ cards-per-hand`, no boosted-magnitude overflow) | Code; the ranges above already satisfy it | Holds |

## Held fixed

| Key | Baseline | Reason |
|---|---|---|
| `game.rules.max-eras` | 5 | The five-era arc is the structure duration targets are written against (criterion D-05); era 5 has its own rules |
| `game.rules.events-per-era` | 3 | Fixes how many events an era resolves; with `max-eras` it sizes the event deck (criterion G-03) |
| `game.rules.min-players`, `game.rules.max-players` | 3, 5 | The case matrix covers exactly these player counts |
| `game.rules.once-era-budgeted-specials` | `ANNIHILATE`, `SEAL`, `CORRUPT`, `MIMIC`, `REWEAVE` | Which specials carry a budget is a rule choice per special, not a numeric dial; a set-valued search would mostly play rule variants design has not proposed |
| `game.rules.hand-deal-forced-types` | none | Forces card types into deals for scripted tests; not a gameplay rule |
| `game.rules.probability.floor`, `game.rules.probability.ceiling` | 0, 90 | Clamping bounds of every outcome; code couples them (`floor + 2 × ceiling ≥ 100`, `2 × floor + ceiling ≤ 100`), and changing them reshapes every card's effect at once |
| `game.rules.probability.band-low-max`, `game.rules.probability.band-medium-max` | 30, 60 | Define the public probability bands, an information rule rather than a balance dial |
| Every timer (`action-round-timer-seconds`, `hand-selection-timer-seconds`, `declaration-timer-seconds`, `paradox-resolution-timer-seconds`, `reconnect-grace-period-seconds`) | as served | Simulations run in logical time and bots act in every window, so durations do not change outcomes |
| Win threshold per player count | one value | `win-score-threshold` is one value for every player count. Version 1 searches it as one value. Making it per player count needs a code change in game-service, worth it only if the search shows the best threshold differs by player count |
| Event content (the future-event catalog) | as served | Content, not rules: it changes the content digest, and the search varies rules only |

## Size

46 parameters are searched independently (Suppress is tied to Push). On their grids, before constraints, the space is
far too large to enumerate, which is why the search samples it (temporal-rift/infrastructure#124, section 5).

## Open questions

None of the entries is left `OPEN`. The ranges are proposals for design approval; the weakest grounded are the
probability-effect magnitudes, which the game design leaves to playtesting.
