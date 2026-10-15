# Balance criterion version 1

Status: proposed by design for the configuration search (temporal-rift/infrastructure#124, decided in
temporal-rift/simulation-workbench#45). It becomes approved when the design owner approves the pull request that adds
it; any amendment is made in that review or in a later version, never by editing an approved version.

The criterion decides whether a rules configuration, played by the baseline bot policies, is clearly defective
(rejected), demonstrably meets the design targets (confirmed), or neither. It ranks candidates for the search and
selects playtest candidates. It says nothing about balance among human players, and no evaluation against it may be
presented as such.

Every metric name below is a metric of analysis version 1 (see "Metrics of analysis version 1" in the README), except
`failure_share`, which this record defines (D-12) and the evaluation adds.

## Terms

- **Cohort**: one variant, one bot policy, one player count `p`, and a pooling level as in the README's "Populations"
  (pooled, per seat, per faction set, per faction set and seat).
- **Line**: one metric with one dimension value in one cohort, a band, and a role.
- **Band**: a closed interval `[L, U]`, or a one-sided limit (`≥ L` or `≤ U`) with a normalisation scale `s`.
- **Interval**: the 95% block-bootstrap interval of the line's value, computed by the analysis (2,000 resamples of
  seeds). A line whose value is derived from several report values (the faction ratio, the failure-share sum) uses the
  interval of the derived value recomputed in each of the same resamples, never a combination of separate intervals.

## D-01 Metric set

| Metric | Used for | Why |
|---|---|---|
| `faction_win_rate`, as a ratio to the reference `R` (D-02) | Faction balance | The central balance question: does any faction win much more or less than an average seat |
| `shared_win_rate` | Guard on the faction reference | `R` absorbs shared wins, so a configuration where everyone shares victory would look perfectly balanced without this line |
| `eras` (MEAN, QUANTILE 0.1) | Duration | Game length in the unit the rules are built on |
| `ending_cause_rate` (`TIMELINE_STABILIZED`, `TIMELINE_COLLAPSED`) | How games end | Special endings are designed but must stay the exception to normal victory |
| `ending_cause_rate` (`RESOLUTION_FAILED`, `DECK_EXHAUSTED`) | Abnormal endings | The rules give every designed ending a winner; these endings are defects |
| `paradox_findings` (MEAN, no dimension) | Paradox frequency | The core mechanic must occur, and not dominate |
| `paradox_cascade_rate` | Paradox resolution | The resolution phase must be a real contest, not a foregone result |
| `failure_share` (new) | Games that never ended | Without it, a configuration that stalls games looks healthy because the stalled games leave every denominator |

Deliberately excluded:

- `rounds`: a round counts the distinct action rounds that took a decision, and games end at era boundaries, so it is
  about three times `eras` and adds no independent information.
- `no_winner_rate`: it mixes `ALL_PLAYERS_ABANDONED`, which with bots is a harness artefact, with two rule defects.
  The two defect reasons have their own lines (D-07).
- `faction_shared_win_rate`: covered by `shared_win_rate` and the reference.
- `paradox_resolution_rate`: the complement of `paradox_cascade_rate` for this purpose.
- `paradox_findings` per `paradoxType`: the rules declare Impossible Erasure unreachable by accepted play, so any
  occurrence is a correctness defect to fix in the services, not a balance property of a configuration. The per-type
  values stay diagnostics.
- `score`, card, special and declaration metrics: diagnostics for explaining a result. Associations between them and
  winning are observational (README, "Limitations") and must not become targets.
- Per-seat lines: see D-04.

## D-02 Faction reference

**Decision.** For a cohort with player count `p`:

```
R = (total winner seat-games) / (total seat-games)
  = Σ_f numerator(faction_win_rate, f) / Σ_f denominator(faction_win_rate, f)
```

A faction line evaluates the ratio `faction_win_rate(f) / R`. Its band is on that ratio.

- **`n` is the number of seats in the game (`p`)**, not the number of factions appearing in the cohort (all five appear
  at every player count of the full matrix) and not the roster size. A seat-game competes against `p - 1` other seats,
  so with equal factions every seat wins equally often.
- **Shared wins** count once for every winner, as in the metric. They raise `R` and every faction rate together.
- **No-winner games** count in the denominator (every seat-game) and add no winner. They lower `R` and every faction
  rate together.
- `R` therefore generalises `1/p`: it equals `(mean winners per game) / p`. With the full matrix (every faction in
  every seat equally often) it also equals the plain mean of the faction rates, so the ratios average to 1.
- The frequency of shared wins and of no-winner games is constrained by their own lines, not by the faction lines. The
  faction lines measure only how wins are distributed among factions.
- If `R = 0` (no game had a winner), the faction lines are `NO_DATA`.

**Why not compare `faction_win_rate` with `1/p` directly.** Shared wins push every faction above `1/p` and no-winner
games push every faction below it, so a level shift that affects every faction equally would be scored as imbalance.

**Worked example.** One cohort at `p = 3` with the faction set Erasers, Prophets, Weavers, five games, each faction in
one seat of every game (5 seat-games per faction, 15 in total):

| Game | End reason | Winners |
|---|---|---|
| 1 | `WIN_CONDITION_MET` | Erasers |
| 2 | `TIMELINE_STABILIZED` | Prophets and Weavers (shared) |
| 3 | `DECK_EXHAUSTED` | none |
| 4 | `WIN_CONDITION_MET` | Prophets |
| 5 | `RESOLUTION_FAILED` | none |

Winner seat-games: 1 + 2 + 0 + 1 + 0 = 4, so `R = 4 / 15 = 0.267` (`1/p` would be 0.333).

| Faction | `faction_win_rate` | `faction_shared_win_rate` | Ratio to `R` |
|---|---|---|---|
| Erasers | 1/5 = 0.20 | 0/5 = 0.00 | 0.75 |
| Prophets | 2/5 = 0.40 | 1/5 = 0.20 | 1.50 |
| Weavers | 1/5 = 0.20 | 1/5 = 0.20 | 0.75 |

The ratios average to 1. Against `1/p = 0.333` the ratios would be 0.60, 1.20 and 0.60: every faction would look a
fifth weaker purely because two games had no winner, which says nothing about how wins are distributed. In this
cohort `shared_win_rate = 1/5`, and the two no-winner games are separate defects caught by G-02 and G-03.

## D-03 Bands per player count

**Decision.** Every line uses one band for 3, 4 and 5 players.

- The faction ratio already normalises for `p`.
- Duration, ending mix and paradox frequency describe the experience of one game. The intended experience (a game
  that develops over most of the era arc, usually ends in a normal victory, and usually contains a paradox) does not
  depend on the table size. If the configuration needs different values per player count to reach it (for example the
  victory threshold, which the game rules leave open and possibly per player count), that is a parameter of the search,
  not a different target.
- Player counts differ in sample size (one faction set at 5 players), which the evidence rule handles (D-09), not the
  bands.

## D-04 Cohort granularity

**Decision.**

- **Pooled** per player count is the level for every line.
- **Per faction set**: faction lines only, as gates with the wide band, at 3 and 4 players. At 5 players the only
  faction set is the pooled cohort, so no separate line exists. `R` is computed inside the faction set's own cohort.
  These lines catch a configuration where a faction dominates particular opponents while looking balanced pooled.
  They are gates only: per set the samples are a fifth to a tenth of the pooled ones, and scoring 50 noisy
  per-set lines would make the score chase noise.
- **Per seat**: no lines. Decisions are simultaneous and their resolution ignores submission order, so the rules give
  no seat an advantage, and a rules configuration cannot create one. A seat effect would indicate a defect in the
  services or the harness, which seat diagnostics in the report already expose.

## D-05 Duration

**Decision.** `eras` only (see D-01 for `rounds`), with two lines:

- **Mean, band `[3.5, 4.5]`, scored.** The rules place the earliest score-threshold victory in era 3 and most faction
  objectives between eras 3 and 5. A mean centred on era 4 means the typical game uses most of the arc without
  routinely running to the cap.
- **Quantile 0.1, lower limit 3, gate.** If at least a tenth of games end by era 2, several factions (Erasers need four
  eras of annihilations, Weavers three successive eras, Revisionists and Activists three eras) cannot reach their own
  objectives before someone else ends the game. That is a defect, not a tuning preference. Under `random-v1` the same
  gate catches a faction that wins by era 2 without making a choice.

The bands are absolute eras, whatever era maximum a candidate sets: length is what players experience. A candidate
that lowers the maximum below these bands fails them on purpose.

The era cap censors `eras`; this is a real rule, not a measurement limit, so no correction is made. Games that reach
the cap without a normal winner are bounded by L-03 (`TIMELINE_STABILIZED`).

## D-06 Paradox

**Decision.** "Paradox rate" is `paradox_findings` (MEAN, no dimension): findings per game, band `[1, 3]`, scored.
The resolution outcome is a separate scored line: `paradox_cascade_rate`, band `[0.25, 0.75]`.

- Below one finding per game, a game often passes without the mechanic the game is named for; above three, a
  resolution phase occurs in most eras of a typical four-era game and stops being an event.
- If fewer than a quarter of findings cascade, the cascade threat is empty and nobody needs to fight for resolution; if
  more than three quarters cascade, the resolution phase is a formality and cascades become frustration (an open
  question of the game design that this band keeps measurable).
- These bands have the weakest grounding in the game rules of all v1 values: the rules state the mechanic's role but no
  intended frequency. They are scored only (never gates) and carry low weight for that reason, and they are the first
  values playtesting should revisit.

Collapse (three distinct cascaded events) is bounded separately by L-04.

## D-07 Games without a winner

**Decision.** Every designed ending has at least one winner in every faction set, so the target for an ending without
a winner is zero. The end reasons are treated differently:

| End reason | Treatment | Why |
|---|---|---|
| `RESOLUTION_FAILED` | Gate, `≤ 0` (G-02) | The game could not resolve: a defect of the configuration or of the services; either way the configuration cannot be confirmed with it |
| `DECK_EXHAUSTED` | Gate, `≤ 0` (G-03) | Unreachable with the default deck and era maximum; it appears when a configuration's era count or carry-over outgrows the event deck, which is a configuration defect |
| `ALL_PLAYERS_ABANDONED` | Not a line; any occurrence makes the evaluation invalid (D-09) | Bots never leave a game, so this ending means the harness lost its seats. It says nothing about the rules and the game is not a valid sample |

A limit of 0 means such a line `MEETS` only when the reason never occurred, and `FAILS` only when it occurs
consistently across resamples. One rare occurrence leaves it `INCONCLUSIVE`, which blocks confirmation (D-09) and
calls for investigating that case.

## D-08 Lines, roles and aggregation

### Lines

`fg` is `faction-greedy-v1`, `rnd` is `random-v1`. "All" in the players column means one band for 3, 4 and 5 players,
evaluated as a separate line for each. "Each faction" means one line per faction listed in the cohort.

| Id | Metric | Dimension | Policy | Players | Pooling | Band | Role | Weight | Rationale |
|---|---|---|---|---|---|---|---|---|---|
| G-01 | `failure_share` | sum of `RUNNER_TIMEOUT` and `POLICY_EXHAUSTED` | fg, rnd | All | Pooled | `≤ 0.02`, s = 0.02 | GATE | | Games that never end are a defect; 2% keeps survivorship bias well below the narrowest band (D-12) |
| G-02 | `ending_cause_rate` | `RESOLUTION_FAILED` | fg, rnd | All | Pooled | `≤ 0`, s = 0.01 | GATE | | Every designed ending has a winner (D-07) |
| G-03 | `ending_cause_rate` | `DECK_EXHAUSTED` | fg, rnd | All | Pooled | `≤ 0`, s = 0.01 | GATE | | The era count must fit the event deck (D-07) |
| G-04 | `eras` | quantile 0.1 | fg, rnd | All | Pooled | `≥ 3`, s = 1 | GATE | | Endings by era 2 shut out the slower faction objectives (D-05) |
| G-05 | `faction_win_rate` ÷ `R` | each faction | fg | All | Pooled | `[0.5, 2.0]` | GATE | | Twice or half as likely to win as an average seat is a broken faction under any tuning philosophy |
| G-06 | `faction_win_rate` ÷ `R` | each faction | fg | 3, 4 | Per faction set | `[0.5, 2.0]` | GATE | | The same defect in a particular matchup (D-04) |
| G-07 | `faction_win_rate` ÷ `R` | each faction | rnd | All | Pooled | `≤ 2.0`, s = 1 | GATE | | A faction that wins twice its share while choosing at random wins without decisions; losing under random play is expected for factions whose route needs intent, so no lower limit |
| G-08 | `shared_win_rate` | | fg | All | Pooled | `≤ 0.5`, s = 0.5 | GATE | | If most games are shared, victory stops distinguishing players and the faction reference hides it |
| L-01 | `faction_win_rate` ÷ `R` | each faction | fg | All | Pooled | `[0.8, 1.25]` | SCORED | 4/15 per line | Within the same factor (1.25) of an average seat either way; tighter targets would tune to bot behaviour that playtesting will move anyway |
| L-02 | `eras` | mean | fg | All | Pooled | `[3.5, 4.5]` | SCORED | 2/3 per line | Typical game uses most of the five-era arc (D-05) |
| L-03 | `ending_cause_rate` | `TIMELINE_STABILIZED` | fg | All | Pooled | `≤ 0.2`, s = 0.2 | SCORED | 1/3 per line | Running out the arc without a normal winner is a designed fallback, not the usual ending |
| L-04 | `ending_cause_rate` | `TIMELINE_COLLAPSED` | fg | All | Pooled | `≤ 0.1`, s = 0.1 | SCORED | 1/3 per line | Collapse is the paradox system's threat; it must stay possible and rare, or paradoxes override faction goals |
| L-05 | `shared_win_rate` | | fg | All | Pooled | `≤ 0.2`, s = 0.2 | SCORED | 1/3 per line | Shared victory is legitimate at a scoring boundary but should be the minority of wins |
| L-06 | `paradox_findings` | none | fg | All | Pooled | `[1, 3]` | SCORED | 1/3 per line | The core mechanic occurs in a typical game without becoming routine (D-06) |
| L-07 | `paradox_cascade_rate` | | fg | All | Pooled | `[0.25, 0.75]` | SCORED | 1/3 per line | Resolution is a contest whose outcome is not foregone (D-06) |

Together, L-03 and L-04 leave at least 70% of games to a normal victory; L-02, L-03 and G-04 are jointly satisfiable
(for example 40% of games ending in era 3, 40% in era 4 and 20% in era 5 give a mean of 3.8).

### Weights

Weights are set per group and split equally across the group's lines, so every player count and every faction counts
the same:

| Group | Lines | Group weight | Share |
|---|---|---|---|
| Faction balance | L-01 (5 factions × 3 player counts) | 4 | 36% |
| Duration | L-02 (3) | 2 | 18% |
| Stabilization, collapse, shared wins | L-03, L-04, L-05 (3 each) | 1 each | 27% |
| Paradox frequency and cascade | L-06, L-07 (3 each) | 1 each | 18% |

Faction balance carries the most weight because it is the property the search exists to fix; duration next, because
an unbalanced length damages every game regardless of faction. Paradox lines carry the least because their bands are
the least grounded (D-06).

### Deviation and verdict per line

- **Deviation** `d`, from the line's point value `x`: 0 inside the band; `(L - x) / w` or `(x - U) / w` outside it,
  where `w = U - L` for a two-sided band and `w = s` for a one-sided limit.
- **Verdict**, from the interval `[a, b]` and the closed band: `MEETS` when `L ≤ a` and `b ≤ U`; `FAILS` when `b < L`
  or `a > U`; otherwise `INCONCLUSIVE`. A line whose value is `INSUFFICIENT_SAMPLE` or `NO_DATA` is `INCONCLUSIVE`;
  a `NO_DATA` line has `d = 0`.

### Aggregate score

```
score = Σ over SCORED lines of  weight × d²
```

Lower is closer to the targets; 0 means every scored point value lies inside its band. Squaring makes one line far
outside its band cost more than several lines just outside theirs, so the search fixes the worst problem first, while
every line still contributes (a worst-line-only rule would leave the search blind to everything except one line).
The score uses point values only and is a ranking aid: it never rejects or confirms anything on its own. The same
report, criterion version and analysis version give the same deviations, verdicts and score.

## D-09 Evidence rule

**Confidence.** Line verdicts use the analysis's 95% two-sided percentile block-bootstrap interval with the seed as
the block. A gate `FAILS` only when the whole interval lies outside its band, so a line whose true value sits exactly
on the edge is wrongly rejected at most about 2.5% of the time; the gate bands are wide enough that true values near
their edges are themselves suspect. This risk is accepted.

**Validity first.** An evaluation of a variant is `INVALID` (neither rejects nor confirms; the run is repeated after
the cause is fixed) when any of these holds:

- some case of the variant did not reach `SUCCEEDED` or `FAILED` (pending, running or cancelled cases leave the
  matrix unbalanced);
- any case failed with `CONTRACT_MISMATCH` or `CONFIGURATION_DRIFT`;
- the `EXECUTION_FAILED` share of a policy and player count exceeds 0.05;
- any game ended `ALL_PLAYERS_ABANDONED`.

**Overall verdict of a valid evaluation.**

| Verdict | Condition |
|---|---|
| `REJECTED` | Any GATE line `FAILS` |
| `CONFIRMED` | Confirmation run (below), every pooled GATE line `MEETS`, no GATE line `FAILS`, and no SCORED line `FAILS` |
| `UNCONFIRMED` | Neither of the above |

How `INCONCLUSIVE` lines count:

- During the search, an `INCONCLUSIVE` line never rejects. A candidate is discarded only by a `FAILS` gate (or by
  evidence-based dominance, which the search defines).
- At confirmation, an `INCONCLUSIVE` pooled gate line prevents `CONFIRMED`: a gate must be shown to hold, not merely
  not shown to fail. `INCONCLUSIVE` per-faction-set gate lines (G-06) and `INCONCLUSIVE` scored lines are allowed and
  listed in the playtest brief: proving every matchup inside the wide band would need many times the pooled sample,
  and a straddling interval at the confirmation sample is itself a playtest flag.

**Confirmation sample.** A confirmation run plays the full case matrix (every faction set at 3, 4 and 5 players,
`CYCLIC` rotation, both policies) on **at least 100 fresh seeds**, fixed before the run starts.

The 5-player faction lines need the most seeds: each faction has 5 seat-games per seed and policy, with `R` near 0.2.
For a faction at parity to `MEET` L-01, the interval half-width must stay below the distance to the nearer band edge
(`0.2 R`, about 0.044), so the standard error must be at most about 0.022. Treating the five seat-games of a seed as
independent, the variance of a seed's mean is about `0.2 × 0.8 / 5 = 0.032`, which needs about 70 seeds. Seat-games
of one seed share their deck and deals and are positively correlated, and `R` is itself estimated, so the minimum is
set at 100. At 3 players the same reasoning needs fewer than 10 seeds.

No optional stopping: the confirmation sample is not extended after looking at its result. A candidate that ends
`UNCONFIRMED` can be confirmed only by a new confirmation run on new fresh seeds, and every confirmation run of a
candidate is reported.

The search's screening sample is a budget decision, not a design value. An evaluation with fewer than two seeds cannot
reject anything, because every line is `INSUFFICIENT_SAMPLE`.

## D-10 Bot policy cohorts

| Policy | Role | Lines |
|---|---|---|
| `faction-greedy-v1` | Primary: approximates players pursuing their faction's goals, so its results are the closest bot evidence of the intended experience | Every gate and every scored line |
| `random-v1` | Robustness: detects defects that do not depend on intent | Gates G-01 to G-04 and G-07 only; no scored lines |

Under random play, faction lower limits and the duration and ending-mix targets do not describe intended play (a
faction whose route needs planning is expected to lose), so `random-v1` is never scored. Stalled games, abnormal
endings, endings by era 2 and a faction winning without choosing are defects whoever plays.

## D-12 Failure share

**Metric.** `failure_share`, statistic RATE, dimension `failureCode` (each of the five codes, or none for all codes),
in every cohort that pools seats (pooled and per faction set):

```
failure_share = cases whose final state is FAILED (with that code) / cases whose final state is SUCCEEDED or FAILED
```

A case's code is the code of its final failed attempt; retried attempts that later succeeded do not count. Cancelled,
pending and running cases are in neither count. The interval is the same seed block bootstrap as every other metric.

**Limit.** The share of counting causes is at most 0.02 per policy and player count (G-01). Games that fail are
missing from every other line, so a failure share `f` can move a rate line by up to about `f`; 0.02 stays well below
the narrowest absolute band edge distance of a faction line (about 0.044 at 5 players) and the width of any other band.

| Code | Counts against the configuration | Why |
|---|---|---|
| `RUNNER_TIMEOUT` | Yes | After its retries, the game did not end within an hour of wall time under logical time: the configuration produces games that stall or run on, which is exactly the survivorship case |
| `POLICY_EXHAUSTED` | Yes | A seat was left in a window where even pass or decline was refused: a human player would be stuck too, so the game state the configuration produced is unplayable |
| `EXECUTION_FAILED` | No; validity limit 0.05 (D-09) | A lane or service call failed: an infrastructure fault presumed independent of the game's content, so it shrinks the sample without biasing it; above 5% that presumption is no longer safe |
| `CONTRACT_MISMATCH` | No; any occurrence invalidates (D-09) | The harness and the services disagree on the contract; no game of the run is trustworthy evidence about the rules |
| `CONFIGURATION_DRIFT` | No; any occurrence invalidates (D-09) | A lane played another case, manifest or faction than planned; the run's provenance is in doubt |

A configuration that crashes a service would surface as `EXECUTION_FAILED` and make its evaluations invalid rather
than rejected; that is a service defect to fix, not a balance outcome.

## Open questions

None of D-01 to D-10 or D-12 is left `OPEN`. Values with the weakest grounding, which the design owner should review
first and playtesting should revisit: the paradox bands (L-06, L-07) and the collapse and stabilization limits (L-03,
L-04). The parameter space (D-11) is decided separately; a parameter that changes the era maximum or the event deck
remains subject to D-05 and G-03 as written.
