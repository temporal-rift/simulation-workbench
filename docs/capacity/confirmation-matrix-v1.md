# Confirmation matrix, version 1

The criterion version 2 confirmation matrix played on the baseline rules on the simulator, as a confirmation plays it:
17,160 games, 104 seeds. It measures the confirmation's duration, the within-seed correlation that sizes its sample, the
baseline's faction ratios on the two lines that depend on the new table, and the baseline's verdict. It is the
measurement behind [budget record version 3](../budget/v3.md). The analysis is
[confirmation-matrix-v1.py](confirmation-matrix-v1.py).

## Method

- **Matrix.** Seeds `5001` to `5104`, player counts 3, 4 and 5, every faction set, `CYCLIC` rotation, the three
  `CONFIRMATION` tables of criterion version 2 (`faction-greedy-v1` on every seat, `random-v1` on every seat and
  `random-seat-vs-faction-greedy-v1`), one variant (the baseline rules), logical timing: 104 × 165 = 17,160 cases. It was
  frozen as an experiment through the REST API with those explicit seeds, which is the manifest
  `startConfirmation` freezes, except for the seeds: a campaign derives its confirmation seeds from its master seed.
- **Run.** Run `0db01a96-6b2f-4ee3-95b5-8899b4acd08d`, started through the REST API, 12 slots, concurrency 12, a case
  limit of 300 s (the production default is 3,600 s; see "Interruptions"). Wall time is the run's `finishedAt` less its
  `startedAt` as the workbench records them: 2026-11-08T22:22:04Z to 2026-11-09T02:41:44Z, 4 h 19 m 40 s.
- **Machine.** An Intel Core i7-13700H (14 cores, 20 threads) with 32 GB. The workbench JVM (Temurin 26.0.2) was capped
  with processor affinity for 8 logical processors, `-XX:ActiveProcessorCount=8` and `-Xmx12g`, as in
  [simulator-v2.md](simulator-v2.md). It shared the machine with a Postgres container and the user's desktop session.
  From 01:12 local time (00:11Z) the workbench and the Docker processes ran at below-normal priority.
- **Database.** The workbench database ran in a Postgres 16 container on a Windows bind mount. For the first 1.8 h of the
  run its writes were fsynced, and Docker's file sharing then used about 7 cores beside the workbench's 5, which slowed
  the run and the machine; from then on Postgres ran with `fsync`, `synchronous_commit` and `full_page_writes` off, which
  is acceptable for a scratch measurement database. The rate before that point is not a capacity figure.
- **Statistics.** All intervals are 95% percentile bootstrap intervals over seeds, 2,000 resamples, fixed generator
  seed; a resampled seed keeps all its games together. `ρ` is estimated as in
  [five-player-analysis-v2.md](five-player-analysis-v2.md); the faction ratios follow the definition of
  [criterion version 2](../balance-criteria/v2.md) (every winner scores a whole win, `R` is the mean winners per game
  over `p`). The evaluation and the line verdicts are the workbench's own (`GET /api/v1/runs/{runId}/evaluation`,
  `mode=CONFIRMATION`, criterion version 2).

## Builds

The merged builds of [simulator-v2.md](simulator-v2.md), second set, for which the simulator fidelity check `dfc71430`
passed (seed 1, 330 of 330 reproductions matched) and `GET /api/v1/simulator/fidelity` answered `established: true` at
the start of the run.

| Service | Source revision | Simulator jar digest |
|---|---|---|
| game-service | `e1962719e30dee3abed2f9727f70fdf8a01dfa16` | `sha256:79d9075c721aff53408d142b0c6d6a9b5a803e8bf9e1c32289ce79f690d4a102` |
| timeline-service | `010718fda43e729d9f9e6d4b3a23457d6294ea47` | `sha256:a15c8b49b9a7dff102f34b6ec11cd40adf14f0969f374eac9ecc821e0162b193` |
| read-service | `af474c56f5eeb5b75466b198d53686ac190eba07` | `sha256:c8b726c19e83deb7f5b7d726a8a94902215b64c64d77888a444aa938acafa6dc` |

The workbench was `main` at `7a363d4` (the merge of temporal-rift/simulation-workbench#139), jar digest `sha256:16c1ba87…`
(`16c1ba87086b479943e9d2a6a6f645d188c9971aa2397529cc9a353afeb52276`), with the execution lease raised to 10 minutes. Contracts:
session-event 7.2.0, action-event 9.3.0, timeline-event 7.1.0, scoring-event 3.0.0, session-api 3.0.0, action-api 7.1.0,
scoring-api 2.0.1, projection-api 10.1.2 and chains-api 2.0.0. Rules digest `825d507c…`, content digest `bcd07d59…`; policy
bundles `random` 1.0.0 and `faction-greedy` 1.0.0.

## Cases and duration

The run ended `COMPLETED` with **17,150 cases succeeded and 10 failed**. The 10 failed cases were replayed in two
supplementary runs of one seed each (seed 5023: run `327bdad6-f2e7-4c77-8a50-e94284c3659a`, 165 of 165 succeeded; seed
5024: run `c1a9bbb0-6920-4855-80fd-14ff0ce589cc`, 165 of 165 succeeded), and the analysis below uses those results in place of the failed ones, matched by seed,
player count and seating. The games are deterministic in their seed, so the replay plays the same game. Neither
replay is a capacity measurement. The workbench's own evaluation was taken on the run as played, with the 10 failures
counted as execution failures.

| Part | Games | Hours | Games per hour |
|---|---:|---:|---:|
| Whole run, wall time | 17,150 | 4.33 | 3,960 |
| From 00:20Z, after the database fix | 13,177 | 2.36 | 5,579 |
| From 01:00Z | 10,584 | 1.70 | 6,243 |
| Planned, `Rs` | 17,160 | 3.18 | 5,402 |

At the steady rate the 17,160 games take 3.08 h, or 2.75 h at the best sustained rate. The whole-run figure includes
two workbench restarts, a cold JVM after each, a database that fsynced every write and the first 1.8 h of a loaded
machine. The rate rose while the JVM warmed up: the half-hour bins grew from 1,832 games (00:30Z) to 3,561 (01:30Z), and the
best full hour (01:00Z) played 7,080. The run did not leave `Rs` behind: 3.18 h is a conservative planning figure for this matrix, and 4.33 h
is what this run took. Peak heap and memory were not recorded.

## Within-seed correlation

`ρ` of one faction's seat-games clustered by seed, averaged over the factions whose win indicator varies; the winners of
a game share one win equally, as in the five-player analysis.

| Table | Players | Seat-games per seed | `ρ` | 95% interval |
|---|---:|---:|---:|---|
| `faction-greedy-v1` | 3 | 18 | 0.0104 | [−0.0049, 0.0246] |
| `faction-greedy-v1` | 4 | 16 | −0.0087 | [−0.0221, 0.0042] |
| `faction-greedy-v1` | 5 | 5 | 0.0009 | [−0.0130, 0.0349] |
| `random-v1` | 3 | 18 | 0.0009 | [−0.0074, 0.0084] |
| `random-v1` | 4 | 16 | −0.0016 | [−0.0106, 0.0064] |
| `random-v1` | 5 | 5 | −0.0129 | [−0.0432, 0.0151] |

At five players ACTIVISTS wins no `faction-greedy-v1` game, so its pair carries no information and is left out, as
in the five-player analysis. The five-player estimate of that analysis, 0.079 on 80 seeds, is not reproduced.

## Re-sizing

`n⁰` is the independent-seat-game sample of G-05 for a faction at ratio 0.8 (95% confidence, 90% power), as criterion
version 2 computes it: `⌈(1.96 + 1.28)² π (1 − π) / m / (0.3 R)²⌉` with `R = 1/p` and `π = 0.8 R`. The rule is
`⌈n⁰ (1 + (m − 1) ρ)⌉ > 104`.

| Players | `n⁰` | Seeds at `ρ` | Seeds at the lower end | Seeds at the upper end | Above 104? |
|---:|---:|---:|---:|---:|---|
| 3 | 12 | 15 | 12 | 18 | No |
| 4 | 19 | 19 | 19 | 21 | No |
| 5 | 79 | 80 | 79 | 91 | No |

No player count needs more than 104 seeds, at the estimate or at either end of its interval. The record does not change
the sample.

## G-05 on `faction-greedy-v1`

Faction win rate over `R`, with the band `[0.5, 2.0]`:

| Faction | 3 players | 4 players | 5 players |
|---|---|---|---|
| ACTIVISTS | 0.629 [0.593, 0.663] | 0.449 [0.419, 0.481] | 0.000 [0.000, 0.000] |
| ERASERS | 2.225 [2.196, 2.255] | 3.044 [2.996, 3.096] | 4.885 [4.802, 4.952] |
| PROPHETS | 0.287 [0.231, 0.342] | 0.177 [0.141, 0.215] | 0.010 [0.000, 0.029] |
| REVISIONISTS | 0.871 [0.837, 0.903] | 0.635 [0.605, 0.666] | 0.038 [0.010, 0.076] |
| WEAVERS | 0.989 [0.961, 1.015] | 0.694 [0.665, 0.728] | 0.067 [0.019, 0.131] |

## G-07 version 2 on the random seat

The faction's win rate on the random seat of `random-seat-vs-faction-greedy-v1` (seat 0, one seat-game per rotation
cycle) over `R` of the whole mixed table, with the limit 1.0. Seat-games per faction: 624 at 3 players, 416 at 4 and
104 at 5.

| Faction | 3 players | 4 players | 5 players |
|---|---|---|---|
| ACTIVISTS | 0.284 [0.224, 0.346] | 0.318 [0.236, 0.400] | 0.337 [0.147, 0.557] |
| ERASERS | 0.072 [0.040, 0.108] | 0.037 [0.007, 0.081] | 0.000 [0.000, 0.000] |
| PROPHETS | 0.040 [0.020, 0.065] | 0.007 [0.000, 0.022] | 0.000 [0.000, 0.000] |
| REVISIONISTS | 0.184 [0.133, 0.243] | 0.229 [0.154, 0.316] | 0.262 [0.076, 0.454] |
| WEAVERS | 0.136 [0.091, 0.181] | 0.155 [0.096, 0.228] | 0.075 [0.000, 0.189] |

Every value and every upper end is below 1.0: all 15 G-07 lines are `MEETS`.

## Verdict

The baseline's verdict in confirmation mode under criterion version 2 is **`REJECTED`**, score 27.50. The evaluation is
valid and the sample is satisfied (104 of 104 seeds, full matrix). Of 149 lines, 80 are `MEETS`, 65 `FAILS` and 4
`INCONCLUSIVE`:

| Line | `MEETS` | `FAILS` | `INCONCLUSIVE` |
|---|---:|---:|---:|
| G-01 | 8 | 0 | 1 (five players: 0.0096, interval [0, 0.029]) |
| G-02, G-03, G-04, G-07, G-08 | 9, 9, 6, 15, 3 | 0 | 0 |
| G-05 | 5 | 10 | 0 |
| G-06 | 12 | 35 | 3 |
| L-01 | 2 | 13 | 0 |
| L-02, L-03, L-04 | 3, 3, 3 | 0 | 0 |
| L-05 | 2 | 1 (3 players: 0.261, interval [0.246, 0.277], limit 0.2) | 0 |
| L-06 | 0 | 3 (0.137, 0.164 and 0.204 against `[1, 3]`) | 0 |
| L-07 | 0 | 3 (0.129, 0.035 and 0.019 against `[0.25, 0.75]`) | 0 |

The 10 `FAILS` of G-05 are the lines of the table above outside `[0.5, 2.0]`: all five factions at five players, ERASERS
at every count, PROPHETS at 3 and 4 and ACTIVISTS at 4. The G-01 line at five players is the one with the failed cases
below. A verdict on the replayed cases is not available from the endpoint; the table above is computed from the merged
cases and agrees with the endpoint's G-05 and G-07 values to the third decimal, except G-07 at five players (by at most 0.003),
where the endpoint's run lacks the replayed mixed cases.

## Failed cases and interruptions

**The 10 failed cases.** All ended `RUNNER_TIMEOUT` ("The case did not reach an authoritative ending in time") after
the 300 s case limit, and every one was the second attempt of a case that had been playing when the workbench was stopped
to move the database off its fsynced mount. First attempts did not fail in any of the 17,160 cases.

| Case | Seed | Players | Table | Seating |
|---|---:|---:|---|---|
| `03f6d4d8-0ae7-450d-82ef-5ed6c2eff49d` | 5023 | 4 | mixed | WEAVERS, ACTIVISTS, ERASERS, REVISIONISTS |
| `4d183159-c6f6-4970-b386-b20a7f779b66` | 5023 | 4 | mixed | PROPHETS, REVISIONISTS, WEAVERS, ERASERS |
| `146fa7b7-a70a-49a7-b709-89cc78b49e2f` | 5023 | 4 | mixed | REVISIONISTS, WEAVERS, ERASERS, PROPHETS |
| `58011069-35f3-4033-a22e-041ef5314d0b` | 5023 | 4 | mixed | WEAVERS, ERASERS, PROPHETS, REVISIONISTS |
| `70878b32-67d4-4d8f-90e2-b1a5827a4426` | 5023 | 5 | mixed | ACTIVISTS, ERASERS, PROPHETS, REVISIONISTS, WEAVERS |
| `fc81367d-0094-45fe-a687-c7b00322f90e` | 5023 | 5 | mixed | ERASERS, PROPHETS, REVISIONISTS, WEAVERS, ACTIVISTS |
| `49deef8e-babb-42a6-99e0-093d61390065` | 5023 | 5 | mixed | PROPHETS, REVISIONISTS, WEAVERS, ACTIVISTS, ERASERS |
| `cb906adf-ae6d-4a0e-a56b-24d27724a1ed` | 5023 | 5 | mixed | REVISIONISTS, WEAVERS, ACTIVISTS, ERASERS, PROPHETS |
| `810dffa2-600b-4c98-b448-9da4967e7b18` | 5023 | 5 | mixed | WEAVERS, ACTIVISTS, ERASERS, PROPHETS, REVISIONISTS |
| `94071193-9968-48e1-b955-2c4529821a83` | 5024 | 3 | `faction-greedy-v1` | ACTIVISTS, ERASERS, PROPHETS |

**Cause.** A command ledger is keyed by case, seat and decision window, and it answers a slot an earlier attempt had
accepted with "already accepted" without sending it. A real lane keeps the game between attempts, so that is right
there. The simulator keeps a game only in memory, so a second attempt of an interrupted case plays a new game that
never receives the commands the ledger skips, plays on to the last window and waits for an ending that does not
come. Of 12 second attempts after the stop, 10 failed this way; an earlier stop of the same kind left nine such cases
running for 36 minutes each until they were cancelled, which is why the case limit was lowered to 300 s for this run (a
normal case takes 3 to 67 s). It was not caused by a seed, by load, or by the game services.

**Other interruptions.** The first two attempts to run the matrix were set to `INTERRUPTED` by the workbench, because a
case's 60 s lease ran out while 12 slots were starting their services at once and no checkpoint had yet renewed it; the
run above used a lease of 10 minutes. The first run of the matrix, with the production case limit of 3,600 s, was
cancelled after 1,367 cases succeeded (none failed) and replayed from the start under the 300 s limit; its games are
not used. The evidence
artifact insert logs a duplicate-key error when two workers store the same manifest at once; the insert tolerates it and
it did not interrupt a run.

## Limits

- One run, one machine, one set of builds. A machine with no other load was not available for the first 1.8 h, so the
  whole-run rate is a lower bound of what the simulator plays on its reference spec. The steady rate is above it.
- The 10 replayed cases are played games of the same seeds, not part of the run that was timed.
- The correlation averages the factions whose win indicator varies; at five players that is four of five under
  `faction-greedy-v1`, and ERASERS wins nearly all of its games, so the pairs carry little information, as in the
  five-player analysis.
