# Five-player analysis, version 2

The two figures the confirmation-sample rule of budget record version 1 rests on, measured at five players on the
simulator: the within-seed correlation of seat-games, `ρ`, and the highest faction ratio under `random-v1`. Version 1
assumed `ρ ≤ 0.07` and a highest ratio `≤ 1.6`.

## Data

- **Run.** The five-player cases of the `baseline-policy-comparison` matrix over seeds 201 to 280: one faction set (all
  five factions), the 5 cyclic rotations and both policies, which is 80 × 5 × 2 = 800 games, played with a concurrency
  of 8 in a workbench JVM configured with 12 slots (see [simulator-v2.md](simulator-v2.md)). The workbench set the run to
  `INTERRUPTED` after 312 games with no failed case and no error in its log; it was resumed, played the 488 cases
  still pending, and completed.
- **Builds.** The merged builds of [simulator-v2.md](simulator-v2.md): source revisions `e1962719` (game-service),
  `010718fd` (timeline-service) and `af474c56` (read-service), for which the simulator fidelity check `dfc71430` passed
  (seed 1, 330 of 330 reproductions matched) and `GET /api/v1/simulator/fidelity` answers `established: true`.
- **Games.** All 800 succeeded; none stalled or failed. 427 ended with `WIN_CONDITION_MET`, 372 with
  `TIMELINE_STABILIZED` and 1 with `TIMELINE_COLLAPSED`.
- **Ties.** 60 games have more than one winner: 50 under `random-v1` (12.5% of its games) and 10 under
  `faction-greedy-v1` (2.5%). The games average 1.14 and 1.03 winners respectively.

## Definitions

- **Seat-game.** One faction's seat in one game. Each faction plays 5 seat-games per seed and policy, one per
  rotation.
- **Win.** A game gives one win in total; its winners share it equally, so a sole winner scores 1 and each of two
  winners scores ½. The win rates of the five factions then sum to 1 and parity is 0.2. Crediting every winner with a
  whole win is reported as a sensitivity check below.
- **Faction ratio.** A faction's win rate over a policy's games, divided by the parity rate 0.2. A faction at parity has
  ratio 1.
- **`ρ`.** The within-seed correlation of one faction's seat-games, the quantity budget record version 1 scales its
  seeds by: with `m = 5` seat-games per seed the seeds grow by `1 + (m − 1) ρ = 1 + 4ρ`.

## How `ρ` is estimated

For one policy and one faction, the 80 seeds are 80 clusters of `m = 5` seat-game values. The one-way random-effects
intraclass correlation of those clusters is

```
ρ = (MSB − MSW) / (MSB + (m − 1) MSW)
```

with `MSB` the between-seed and `MSW` the within-seed mean square. The figure reported is the mean of that estimate over
the policy-faction pairs whose win indicator varies at all. All 10 pairs vary in this run (5 under each policy),
because ACTIVISTS wins a few `faction-greedy-v1` games.

The estimate is taken within each faction. Pooling the seat-games of all factions into one set of groups would count the
differences between factions as between-seed variance, mostly the fact that ERASERS wins about 97% of the
`faction-greedy-v1` games, and it is not the correlation the rule needs.

The standard error and the 95% interval are a bootstrap over seeds: 2,000 resamples of the 80 seeds with replacement,
each resampled seed keeping all its factions and policies together because the factions of a game are not independent,
and percentile intervals.

## Result: `ρ`

| Games | `ρ` | Standard error | 95% interval | Pairs used |
|---|---:|---:|---|---:|
| both policies | **0.037** | 0.023 | [−0.006, 0.077] | 10 |
| `random-v1` | −0.005 | 0.015 | [−0.036, 0.024] | 5 |
| `faction-greedy-v1` | 0.079 | 0.048 | [0.001, 0.175] | 5 |

By pair: `random-v1` ACTIVISTS 0.036, ERASERS −0.024, PROPHETS −0.046, REVISIONISTS 0.000, WEAVERS 0.010;
`faction-greedy-v1` ACTIVISTS −0.002, ERASERS 0.154, PROPHETS 0.000, REVISIONISTS −0.005, WEAVERS 0.245.

- **Precision.** The standard error of the pooled `ρ`, 0.023, is below the 0.03 the run was sized for. The
  `faction-greedy-v1` figure alone, 0.048, is above it: ERASERS wins nearly every game and the other factions win
  few, so those pairs carry little information.
- **Against the assumption.** `ρ ≤ 0.07` holds for the estimate, 0.037, but **the upper end of its interval, 0.077,
  is above 0.07**; the interval does not exclude a correlation that raises the sample. Negative values are sampling
  noise around a small true correlation. The correlation sits in `faction-greedy-v1`, 0.079, and is absent under
  `random-v1`.
- **Confirmation sample.** `max(100, ⌈79 × (1 + 4ρ)⌉)` is `max(100, 91) = 100` seeds at `ρ = 0.037` and
  `max(100, 104) = 104` at the upper end, 0.077. The 79-seed term exceeds 100 above `ρ = 0.067`. The sample stays at
  100 seeds at the estimate; at the upper end of the interval it would be 104.

## Result: faction ratios

`random-v1`, win rate over 0.2 with the 95% bootstrap interval over seeds:

| Faction | Ratio | 95% interval |
|---|---:|---|
| REVISIONISTS | **2.202** | [1.969, 2.435] |
| ACTIVISTS | 1.821 | [1.577, 2.058] |
| WEAVERS | 0.544 | [0.387, 0.700] |
| PROPHETS | 0.275 | [0.185, 0.371] |
| ERASERS | 0.158 | [0.088, 0.235] |

**The highest faction ratio under `random-v1` at five players is 2.20 (REVISIONISTS), 95% interval [1.97, 2.44].** The
interval of the largest of the five ratios, which allows for choosing the highest, is [2.00, 2.44]. Both are above the
1.6 the record assumed, and ACTIVISTS at 1.82 is above it too. By the record's rule the confirmation sample stays at
100 seeds and a candidate whose random-play faction is above 1.6 may end `UNCONFIRMED`. The record's line for
`random-v1` itself, a ratio of at most 2.0, lies at the lower end of REVISIONISTS' interval.

`faction-greedy-v1` is far more skewed: ERASERS 4.84 [4.74, 4.93], WEAVERS 0.09 [0.03, 0.18], REVISIONISTS 0.03
[0.00, 0.08], ACTIVISTS 0.02 [0.00, 0.05] and PROPHETS 0.01 [0.00, 0.04].

## Sensitivity: whole win for every winner

Crediting every winner with a whole win makes the rates sum to the games' mean winners (1.14 under `random-v1`), so
parity is then 0.227 and ratios taken against 0.2 are about 14% too high there. It gives `ρ = 0.043`
(standard error 0.023, interval [0.001, 0.085]) and a highest `random-v1` ratio of 2.50 [2.28, 2.74]. Neither changes
a conclusion: the highest ratio stays above 1.6, and `ρ` stays near the 0.07 the sample is sized for.

## Limits

- One run, one set of builds, one seed range. The intervals are percentile bootstrap intervals over 80 seeds and are
  not corrected for bias.
- Each `ρ` of a pair rests on 80 clusters of 5, so the pair estimates are noisy; the pooled figure averages them.
- The highest ratio is chosen after seeing the five, which pulls it up slightly; the interval over the maximum is
  reported for that reason.
- The run's concurrency was 8 in a JVM of 12 slots. The games' results are those of the seeds and policies played and
  do not depend on the concurrency; the run's wall time is not a capacity figure and is not used as one.
