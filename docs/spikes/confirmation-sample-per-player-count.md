# Spike: size the confirmation sample per player count

Answers [simulation-workbench#113](https://github.com/temporal-rift/simulation-workbench/issues/113), under
[infrastructure#124](https://github.com/temporal-rift/infrastructure/issues/124), following
[simulation-workbench#110](https://github.com/temporal-rift/simulation-workbench/issues/110). It is a proposal for design
review: no production code changes, and the outcome (adopt, adopt with changes, reject) is recorded on the issue.

Revision read: simulation-workbench `2a8b6bd` (`main` when read). Nothing here is a new measurement. The inputs are
those of `docs/budget/v1.md` and `docs/balance-criteria/v1.md`, and the lane rate and within-seed correlation named
below.

**Verdict: yes, with one condition.** The confirmation can play a different number of seeds per player count and keep
every pooled gate line at 90% power. The seeds are set by the gates at each player count, and G-05 binds at all three.
At the correlations the budget record assumes, the proposed sample is **8,400 games for the candidate and the baseline
together against 22,440**, 36.5 h against 97.6 h on one light lane at 230 games per hour (section 3). The saving
is 59% to 63% for the correlations considered, and it rests on a within-seed correlation at 3 and 4 players that nobody
has measured yet (section 6). A literal 90% per line also lowers the chance that *all* gates meet at once, from about
0.81 to about 0.53; section 3 gives the variant that keeps it near 0.78 for 55.5 h, and recommends it.

## 1. Inputs

| Input | Value | Source |
|---|---|---|
| Normal quantiles | `z = 1.96`; `z_β = 1.28` (90% power), `2.326` (99%) | Budget record v1, "Seeds the confirmation needs" |
| Games per seed and policy, `G_p` | 30, 20, 5 at `p` = 3, 4, 5 | Budget record v1, "The matrix" |
| Seat-games of one faction per seed and policy, `m_p` | 18, 16, 5 | Budget record v1, "The matrix" |
| Least favourable value on target | G-05: ratio 0.8; G-07: ratio 1.6; G-04: share 0.03 or 0.05; G-08: 0.2 | Budget record v1, same section |
| Within-seed correlation of seat-games, `ρ` | 0.07 (the bound v1 assumes) and 0.10 (the case #110 names); `ρ = 0` as the independent reference | #110 has not published its measurement. Replace these when it does; the formulas are in section 2 |
| Light-lane rate | 230 games per hour | The rate in the issue, measured in #110 |
| Full-matrix comparator | 100 seeds × 110 games, for the candidate and again for the baseline: 22,000 games, 95.7 h | The issue; criterion D-09 |

The rule is the one budget record v1 uses for the full-matrix sample, applied once per player count instead of at five
players only:

```
n⁰ = ⌈ (z + z_β)² σ² / h² ⌉         σ² = π (1 − π) / m          (independent seat-games, R known)
n  = ⌈ n⁰ × (1 + (m − 1) ρ) ⌉        (design effect of m seat-games correlated within a seed)
```

with `π` the rate at the least favourable value on target, `h` its distance to the gate's edge, and `m` the number of
observations one seed contributes to the line. The design effect at five players is v1's `1 + 4ρ`.

## 2. Seeds per gate and player count, at 90% power

The 57 pooled gate lines are G-01 to G-04 for both policies and G-08 for `faction-greedy-v1` (one line per player
count each), and G-05 and G-07 (five factions each, at each player count): 6 + 6 + 6 + 6 + 15 + 15 + 3. The five
factions of G-05 and G-07 have equal `m` and equal `π`, so they share a row. G-04 is the share of games that end by
era 2 (its quantile 0.1 is at least 3 exactly when that share is below 0.1), so `m` is the games per seed and policy.

`K = (1.96 + 1.28)² = 10.4976`.

| Gate | `p` | `π` | `m` | `σ²` | `h` | `n⁰ = K σ² / h²` | `n⁰` rounded up |
|---|---:|---:|---:|---:|---:|---:|---:|
| G-05, ratio in `[0.5, 2.0]`, ratio 0.8 | 3 | 0.2667 | 18 | 0.010864 | 0.1000 | 11.40 | 12 |
| | 4 | 0.2000 | 16 | 0.010000 | 0.0750 | 18.66 | 19 |
| | 5 | 0.1600 | 5 | 0.026880 | 0.0600 | 78.38 | 79 |
| G-07, `random-v1` ratio `≤ 2.0`, ratio 1.6 | 3 | 0.5333 | 18 | 0.013827 | 0.1333 | 8.16 | 9 |
| | 4 | 0.4000 | 16 | 0.015000 | 0.1000 | 15.75 | 16 |
| | 5 | 0.3200 | 5 | 0.043520 | 0.0800 | 71.38 | 72 |
| G-04, share by era 2 `< 0.1`, share 0.03 | 3 | 0.03 | 30 | 0.000970 | 0.07 | 2.08 | 3 |
| | 4 | 0.03 | 20 | 0.001455 | 0.07 | 3.12 | 4 |
| | 5 | 0.03 | 5 | 0.005820 | 0.07 | 12.47 | 13 |
| G-04, share 0.05 | 3 | 0.05 | 30 | 0.001583 | 0.05 | 6.65 | 7 |
| | 4 | 0.05 | 20 | 0.002375 | 0.05 | 9.97 | 10 |
| | 5 | 0.05 | 5 | 0.009500 | 0.05 | 39.89 | 40 |
| G-08, `shared_win_rate ≤ 0.5`, rate 0.2 | 3 | 0.2 | 30 | 0.005333 | 0.30 | 0.62 | 1 |
| | 4 | 0.2 | 20 | 0.008000 | 0.30 | 0.93 | 1 |
| | 5 | 0.2 | 5 | 0.032000 | 0.30 | 3.73 | 4 |
| G-01, G-02, G-03 (limit 0, target 0) | 3, 4, 5 | 0 | | | | | 2 (the floor below which every line is `INSUFFICIENT_SAMPLE`, D-09) |

The five-player rows reproduce budget record v1 (79, 72, 13 or 40, 4). The upper side of G-05 (ratio 1.25 against
2.0) needs 3, 5 and 18 seeds at 3, 4 and 5 players and never binds. G-05 is the binding gate at every player count.

**With the correlation.** `ρ` multiplies each `n⁰` by `1 + (m − 1) ρ`: 2.19 at 3 players, 2.05 at 4 and 1.28 at 5
for `ρ = 0.07`; 2.70, 2.50 and 1.40 for `ρ = 0.10`. The binding line, G-05, gives (rounding `n⁰` up first, as v1 does
for 79):

| `p` | `ρ = 0` | `ρ = 0.07` | `ρ = 0.10` |
|---:|---:|---:|---:|
| 3 | 12 | 27 | 33 |
| 4 | 19 | 39 | 48 |
| 5 | 79 | 102 | 111 |

G-04 and G-08, with the same design effect on their own `m`, stay below G-05 at every `p` and `ρ` (the largest, G-04 at
4 players, needs 24 seeds at `ρ = 0.07` and 29 at 0.10). The 5-player column equals v1's `⌈79 × (1 + 4ρ)⌉`: 102 and 111.

## 3. Totals

Games for one run are `2 × (30 n₃ + 20 n₄ + 5 n₅)`. The candidate and the baseline each play one run, on the same
seeds. Hours are games over 230 per hour. The criterion keeps its 100-seed minimum at five players (the table uses
`max(100, n₅)`); section 5 gives the wording.

**Option A: every pooled gate line at 90% power**, as the issue asks.

| `ρ` | Seeds `n₃ / n₄ / n₅` | Games per run | Both runs | Hours | Full matrix: seeds, both runs, hours | Saving |
|---:|---:|---:|---:|---:|---:|---:|
| 0 | 12 / 19 / 100 | 2,480 | 4,960 | 21.6 | 100 seeds, 22,000, 95.7 | 77% |
| 0.07 | 27 / 39 / 102 | 4,200 | 8,400 | 36.5 | 102 seeds, 22,440, 97.6 | 63% |
| 0.10 | 33 / 48 / 111 | 5,010 | 10,020 | 43.6 | 111 seeds, 24,420, 106.2 | 59% |

**Joint power.** v1 argues that a candidate on target is near an edge in about two lines (the weakest greedy faction on
G-05 and the strongest random faction on G-07, both at five players), so 90% per line keeps the chance that both meet
at about 0.81. With a sample sized by the gates at each player count, the same pair of lines is near an edge at all
three player counts: up to six lines at 90%, so the chance that every pooled gate meets falls to at most `0.9⁶ ≈ 0.53`
(G-07 has some slack at 3 and 4 players, so the true figure is somewhat higher). The full-matrix sample hides this: at 3
and 4 players it plays far more seeds than the gates there need, so those lines are near-certain.

**Option B: 90% at five players, 99% at 3 and 4 players.** `z_β = 2.326` multiplies `n⁰` by `18.37 / 10.50 = 1.75`
(20 and 33 seeds at `ρ = 0`). Four more lines at 0.99 cost `0.99⁴ = 0.96`, so joint power is about `0.81 × 0.96 = 0.78`,
within a point or two of the full-matrix sample.

| `ρ` | Seeds `n₃ / n₄ / n₅` | Games per run | Both runs | Hours | Saving against the full matrix |
|---:|---:|---:|---:|---:|---:|
| 0 | 20 / 33 / 100 | 3,520 | 7,040 | 30.6 | 68% |
| 0.07 | 44 / 68 / 102 | 6,380 | 12,760 | 55.5 | 43% |
| 0.10 | 54 / 83 / 111 | 7,670 | 15,340 | 66.7 | 37% |

**Recommendation: adopt option B**, with the conditions in section 6. Option A is the literal answer to "every pooled gate
at 90%", but it achieves it per line and gives up the overall chance that the confirmation succeeds for a good
candidate; if design weighs a 0.53 overall chance acceptable, A saves another 20 points. The search is unchanged: its
screening sample plays the full matrix per seed (budget record v1, open questions).

Savings come almost entirely from 3 and 4 players: they are 100 of the 110 games of a seed, and the full matrix plays
100 seeds of them where the gates need 12 to 83. The tables leave out the 30-seed floor of section 5.

## 4. Does the block bootstrap stay valid?

**Yes, as the analysis already computes it, with one small-sample caveat and one contract gap.**

- **Each cohort resamples its own seeds.** A cohort is one variant, policy and player count (and a pooling level). Its
  game metrics resample "the seeds that have games" in that cohort, with a resampling stream seeded by the cohort key,
  and `failure_share` resamples the seeds that have finished cases there (README, "Uncertainty"; `CohortComputation`).
  A seed that plays only 3 and 4 players is a block in the 3- and 4-player cohorts and does not exist in the 5-player
  cohort. Nothing in the report assumes the cohorts of one run have the same seeds.
- **Every gate line is a single-cohort line.** D-08 evaluates each line "as a separate line" per player count, and a
  derived value (the faction ratio over `R`, the failure-share sum) is recomputed in each resample of its own cohort. No
  gate line combines two player counts, so unequal seed sets cannot make an interval a mixture. The aggregate score uses
  point values only and has no interval.
- **A block is a seed, as before.** The reason for the block, that cases sharing a seed share deck order and deals
  across faction sets and rotations, holds within each player count. Under a nested design (player count `p` plays the
  first `n_p` seeds of one list) the seed sets are nested, which does not matter to a per-cohort interval; it matters
  only to how the cohorts' verdicts correlate. Section 3 treats them as independent; a positive correlation
  would only raise the joint figure.
- **No selection by outcome.** The prefix lengths `n_p` and the seed list are fixed before the run, so no seed is
  dropped or added after looking at results. This keeps the no-optional-stopping rule intact.
- **Comparison and pairing.** A baseline and a candidate case pair when they share seed, player count, policy and
  seat-to-faction assignment, and a seed is matched in a cohort only when every pair of that cohort succeeded on both
  sides. Pairing is therefore already per cohort; both sides must play the same `n_p` seeds, which the equality rule
  for comparable runs (equal seeds, player counts and faction sets) enforces if the per-player-count lists are part of
  what it compares.
- **Caveat: a percentile bootstrap over few blocks.** The sample sizes use normal theory, and v1 states that the
  bootstrap is only "expected to behave alike". With 12 to 27 seed blocks at 3 players the percentile interval
  under-covers more than with 100. The wording in section 5 therefore includes a floor of 30 seeds per player count,
  to be confirmed by a coverage simulation before the criterion is approved. The floor binds only when `ρ` is near zero
  or in option A at 3 players, and adds at most 1,520 games per run (option A at `ρ = 0`).
- **Gap: the experiment cannot express it.** A manifest has one `seeds` array crossed with its `playerCounts` and faction
  sets, the matrix preview and the case count multiply them, and the evaluator's `ConfirmationSample` holds one
  `requiredSeeds`, one `seeds` and a boolean `fullMatrix`. A per-player-count sample needs the manifest to carry seeds
  per player count, and the evaluator to check each player count against its own minimum. Playing three experiments
  instead does not work: an evaluation covers one variant of one run, and a comparison needs equal experiments.

## 5. What the criterion says, and the wording it would need

The current criterion forbids nothing about unequal seeds by name. It forbids it by construction: "full case matrix ...
on at least 100 fresh seeds" makes every seed play every player count, and the no-optional-stopping sentence applies to
"the confirmation sample" as one number. A new version (criterion version 2; an approved version is never edited) changes
these sentences of D-09, D-03 and D-12 and keeps the rest.

| Where | Current sentence | Replacement |
|---|---|---|
| D-09, "Confirmation sample", first sentence | "A confirmation run plays the full case matrix (every faction set at 3, 4 and 5 players, `CYCLIC` rotation, both policies) on **at least 100 fresh seeds**, fixed before the run starts." | "A confirmation run plays, for each player count `p` of 3, 4 and 5, every faction set of `p` players in every `CYCLIC` rotation under both policies, on the first `n_p` seeds of one list of fresh seeds. The list and `n_3`, `n_4` and `n_5` are fixed before the run starts, are the same for the candidate and for the baseline, and satisfy the sizing rule below. A seed beyond `n_p` plays no case of player count `p`; its absence is not a missing case." |
| D-09, same paragraph, second and third sentences (derivation) | "The 5-player faction lines need the most seeds: each faction has 5 seat-games per seed and policy, with `R` near 0.2. ... so the minimum is set at 100. At 3 players the same reasoning needs fewer than 10 seeds." | Replace the derivation by the sizing rule: "`n_p = max(f_p, ⌈ n⁰_p (1 + (m_p − 1) ρ_p) ⌉)`, the largest over the pooled gate lines of player count `p`, with `n⁰` the seeds at which a line at its least favourable value on target meets its band at the stated power, `m_p` the observations one seed contributes to the line, and `ρ_p` the measured within-seed correlation at `p`. Power is 90% per line at five players and 99% at 3 and 4 players, so that the chance that all pooled gates meet stays near 0.8. The floors are `f_5 = 100` (the previous minimum, kept), and `f_3 = f_4 = 30`." The figures `n⁰` and the least favourable values are listed in the budget record that applies the version. |
| D-09, "No optional stopping" | "No optional stopping: the confirmation sample is not extended after looking at its result." | "No optional stopping: neither the seed list nor any `n_p` is extended, shortened or reordered after looking at a result, for any player count." The rest of the paragraph (a new run on new fresh seeds for an `UNCONFIRMED` candidate, every run reported) is unchanged. |
| D-09, "Validity first", first bullet | "some case of the variant did not reach `SUCCEEDED` or `FAILED` (pending, running or cancelled cases leave the matrix unbalanced)" | "...(pending, running or cancelled cases leave the planned case set unbalanced)". The planned set is the one in the confirmation sample sentence. |
| D-03, last bullet | "Player counts differ in sample size (one faction set at 5 players), which the evidence rule handles (D-09), not the bands." | "Player counts differ in sample size (one faction set at 5 players) and in the seeds the confirmation plays, which the evidence rule handles (D-09), not the bands." |
| D-09 and D-12, the distance at 5 players | "`0.2 R`, about 0.044" | "`0.2 R`, 0.04". Budget record v1 already lists this correction for the next criterion version. |

The other sentences of D-09 stand: the confidence rule (the interval is per cohort), the verdict table, the treatment of
`INCONCLUSIVE` (a pooled gate line must `MEET`; per-faction-set G-06 lines and scored lines may be `INCONCLUSIVE`), and
the screening sentence.

In the workbench, a version 2 criterion document would hold `confirmationMinimumSeeds` per player count and the
evaluator's `ConfirmationSample` would check `n_p` per player count instead of a boolean full matrix; neither is changed
by this spike.

## 6. Risks

1. **`ρ` at 3 and 4 players is not measured.** #110 measures it at five players, from a dedicated five-player run, and
   the table applies the same `ρ` at `m` = 18 and 16, where the design effect is `1 + 17ρ` and `1 + 15ρ`. Seat-games of
   one faction at 3 players come from six faction sets in three rotations each, and may be less or more correlated than
   five rotations of one set. At `ρ₃ = 0.20` the 3-player need is `12 × 4.4 = 53` seeds, not 27. **Condition for adoption:
   the dedicated run also measures `ρ` at 3 and 4 players** (a run of the same size per player count; the per-seed cost
   at 3 and 4 players is the full 30 and 20 games per policy, so it is the larger run), or the sizing is repeated with
   those values before the criterion is approved.
2. **The least favourable values are measured at five players only.** The random-play ratio of 1.6 and the greedy ratio
   of 0.8 are assumptions for 3 and 4 players too; #110 reports the highest random-play ratio at five players.
3. **Joint power.** Option A lowers it to at most 0.53 (section 3). Option B is the mitigation; dropping the five-player
   floor of 100 would lower it further and is not proposed.
4. **Small-sample bootstrap.** A coverage simulation of the percentile interval at 30 blocks of the 3- and 4-player
   rates is needed before approval (section 4).
5. **More `INCONCLUSIVE` per-set lines.** G-06 (per faction set, 3 and 4 players) is allowed to stay `INCONCLUSIVE` at
   confirmation and is listed in the playtest brief. Per-set samples are a fifth to a tenth of the pooled ones, so
   playing 44 to 54 seeds at 3 players instead of 100 leaves more of them straddling a band edge, and the brief grows.
   The same holds for the scored lines.
6. **Contract and evaluator changes.** The manifest, the matrix preview, the case count, the comparison equality rule
   and the evaluator all assume one seed list for every player count (section 4). The change is inside this repository;
   no other repository's contract moves.
7. **Rates move.** Hours scale with the lane rate. The confirmation duration the budget record returns to product
   changes with the lane rate #110 re-measures.

## 7. Reproduction

Every cell of section 2 follows from the formulas in section 1; for example G-05 at 4 players has
`π = 0.8 / 4 = 0.2`, `σ² = 0.2 × 0.8 / 16 = 0.01`, `h = 0.3 / 4 = 0.075`, `n⁰ = 10.4976 × 0.01 / 0.075² = 18.66`,
rounded up to 19, and `19 × (1 + 15 × 0.07) = 38.95`, rounded up to 39. Games are `2 × (30 n₃ + 20 n₄ + 5 n₅)`; for
`ρ = 0.07` in option A, `2 × (30 × 27 + 20 × 39 + 5 × 102) = 4,200`.
