# Search campaigns

## Search campaigns

A **campaign** searches the rules of a baseline for configurations that meet the balance criterion, on the in-process
simulator. It is a durable process; every decision is a function of its definition and of the games' results, so the
same definition always gives the same log.

| Operation | Meaning |
|---|---|
| `POST /api/v1/campaigns` | Accepts a definition (`simulation:write`, `Idempotency-Key`), which needs only a `name`, a `baseline` and `playerCounts`: the service resolves every other omitted field, `policies` included, and returns it on the created campaign (see "Defaults of a new campaign"); `400 INVALID_CAMPAIGN` with the offending `field` and a `reason` (`UNKNOWN_PARAMETER_SPACE`, `UNKNOWN_BASELINE`, `UNKNOWN_CRITERION`, `EQUAL_MASTER_SEEDS`, `INVALID_VALUE`, the last also for `algorithm.version` `SUCCESSIVE_HALVING_V1`, which only stored campaigns use); the same key with the same request body returns the original campaign (a body that omits an input is not the one that names its resolved value), with another `409 IDEMPOTENCY_CONFLICT` |
| `GET /api/v1/campaigns`, `.../{id}`, `.../{id}/log` | The campaigns newest first, one campaign with the active time it has spent so far, and its decision log in sequence order |
| `POST .../{id}/pause`, `.../resume`, `.../cancel` | Pause a queued or running campaign, resume a paused one, cancel one that is not over; repeating the one that already holds changes nothing, any other state answers `409 INVALID_CAMPAIGN_STATE` |
| `PUT .../{id}/parallelism` | Changes the games played at once; the next game claimed obeys it and games in flight finish. Pause, resume, cancel, this and confirmation all take an `Idempotency-Key`: an accepted command repeated with its key returns the campaign and applies nothing, the key on any other request is `409 IDEMPOTENCY_CONFLICT`, and a refused command claims nothing. The key is claimed in the write that applies the command, so the two exist together or not at all |
| `POST .../{id}/confirmation` | From `SEARCH_COMPLETED` only (`simulation:write`, `Idempotency-Key`): freezes and starts the confirmation of the short list on fresh seeds (see "Confirming the short list") and answers `202` with the campaign now `CONFIRMING`; `409 INVALID_CAMPAIGN_STATE` from any other state; `409 SEED_OVERLAP`, `SIMULATOR_NOT_AVAILABLE` or `POLICY_NOT_AVAILABLE` when it cannot be played, and nothing plays |

**The search.** New campaigns search with `SUCCESSIVE_HALVING_V2`. Round 0 generates `initialCandidates`: the baseline,
then a Latin hypercube sample of the whole parameter space, so a candidate moves many parameters at once. Every candidate
plays the same seeds, which derive from `searchMasterSeed` and extend from round to round, over the full matrix of the
campaign's player counts, faction sets under cyclic seat rotation and policies. A candidate that breaks a range, a step or
a constraint, or makes rules another candidate already makes, is redrawn, never repaired, and its redraws are counted in
the log. When a round is played it is decided at once. A failing gate does not discard a candidate by itself; the
candidates are ranked: those with no failing gate line first, by score (one another of them dominates with evidence is
discarded as `DOMINATED`), then those with a failing gate line by their **gate miss**, then invalid evaluations. The gate
miss is, for each gate line id, the mean deviation of that id's lines with a value (0 inside the band), summed over the
gate ids, so each gate counts equally however many lines it has; every decision records it. The best `⌈n / eta⌉` of the
`n` candidates are kept and play `eta` times as many seeds next round; the rest are discarded as `GATE_FAILS` (with the
failing lines) or `NOT_IN_TOP_FRACTION`. Later rounds add no candidates, so the plan is fixed by `initialCandidates`,
`screeningSeeds`, `eta` and `shortListSize`: the search ends after the first round that keeps at most `shortListSize`
candidates, however long that takes. The kept candidates with no failing gate line are short-listed; one that still
fails a gate is discarded as `GATES_NOT_MET` with its failing lines and gate miss, and one whose evaluation is invalid
as `NOT_IN_TOP_FRACTION`, so a search whose last candidates all fail a gate ends with an empty short list. The campaign
then becomes `SEARCH_COMPLETED`.

A campaign stored with `SUCCESSIVE_HALVING_V1` reads and, if unfinished, finishes as version 1 did: round 0 is the
baseline and one-step perturbations, a failing gate discards, later rounds add mutated and random candidates, and the
search stops on the time budget it recorded or a short list unchanged for two rounds (`BUDGET_EXHAUSTED`).

**Waves.** A round plays as one or more waves, one after another. A wave is a frozen experiment whose variants are the
candidates (labelled by candidate id) and a run of it; the campaign records which candidate plays which seed in which
wave, so a game is never played or counted twice. Pausing, resuming and changing parallelism set the concurrency of the
waves being played: a paused campaign claims no new game and games already playing finish.

**Time.** A campaign has no time limit. `spentSeconds` measures active time: the seconds a campaign spends `RUNNING` are
counted, the time it is queued, paused or the process is down is not.

**Durability.** A worker (`workbench.search.worker-enabled`, default `true`) advances campaigns one persisted step at a
time. A step applies, with everything it records, only if the campaign is still at the version it read, and the
experiment and run of a wave are requested with keys derived from the campaign, so a restart repeats nothing. A queued
campaign starts on the worker's next step. The worker polls every `workbench.search.poll-interval` (default `2s`); a round is split into waves
of at most `workbench.search.max-cases-per-wave` cases (default `50000`), and
`workbench.search.case-wall-timeout-seconds` and `workbench.search.max-rejected-candidates-per-window` bound the cases of
its experiments.

**Confirming the short list.** Each short-listed candidate is the best of many noisy candidates measured on the same
search seeds, so its score is optimistically biased. `startConfirmation` therefore confirms on fresh seeds, which remove
that bias on any lane. It derives the
criterion's `confirmationMinimumSeeds` (104) from `confirmationMasterSeed` alone and
refuses with `409`, having frozen nothing, in this order: `SEED_OVERLAP` when one of them is already in the campaign log,
`SIMULATOR_NOT_AVAILABLE` when the simulator is not enabled, and `POLICY_NOT_AVAILABLE` when no bundle serves a policy the
criterion names. Fidelity records are not consulted. Should the simulator still refuse an
experiment or a run after those checks, for instance after a rebuild on other baseline rules, the answer is
`409 SIMULATOR_NOT_AVAILABLE` with the reason in the detail: the experiments and held runs of earlier candidates then
exist, play nothing, and are reused by a retry.
Otherwise it freezes one experiment for each short-listed candidate, in short-list order, each on the simulator over
those seeds, the full case matrix of the campaign's player counts and the criterion's `CONFIRMATION` tables, each
seat playing the bundle of its policy (the sample is only satisfied when each table was played, whatever policies
the search used), and starts one run per experiment. No baseline is played: a verdict is judged against
the criterion's fixed bands and never against the baseline. The criterion's tables include the mixed table
`random-seat-vs-faction-greedy`, so a candidate over player counts 3, 4 and 5 plays 104 seeds × 165 games = 17,160
games: about 3.2 hours at 12 games at once (5,402 games per hour on the simulator) and about 4.3 hours at the default of
4 (4,003 per hour). The confirmation matrix played at 12 slots at 5,579 games per hour over its last 2.4 hours
([docs/capacity/confirmation-matrix-v1.md](capacity/confirmation-matrix-v1.md)). The runs play one after another, each at
the campaign's `parallelism`, and a change of it applies from the next case on; the worker releases the next run when the
one before it is complete and resumes an interrupted run after a restart. A confirmation recorded when it still played on
the real services keeps its runs, its `baseline` run included, and is evaluated and shown as it was. When every run is complete the worker evaluates each
in `CONFIRMATION` mode, records the verdict and score of each on the campaign (`confirmation.runs[].evaluation`) in one
write and moves the campaign to `COMPLETED`, whatever the verdicts. A confirmation run that fails or is cancelled fails the
campaign (`CONFIRMATION_RUN_FAILED`) and stops the other runs; cancelling a `CONFIRMING` campaign cancels its runs and keeps
the cases already played. A request that crashed after freezing is repeated into the same experiments and runs, because
their idempotency keys derive from the campaign and the candidate (and, for the experiment, the simulator's builds, so a
simulator rebuilt since the attempt freezes anew).

**Policy hunts.** A campaign of `kind` `POLICY_HUNT` hunts one faction's best response at one table size instead of
searching the rules. Its definition names a `faction`, exactly one entry of `playerCounts` and, optionally, a
`policyParameterSpaceVersion` (the newest by default), and it follows the hunt plan, so it accepts no `algorithm` and no
`policies`. A definition without a `kind` is a rules search, and a rules search that names a faction is refused.

- **Candidates.** The first candidate plays the defaults, which is `faction-spread`; the other 63 are a Latin hypercube
  sample of the faction's searched subset of the policy parameter space, every other parameter staying at its default.
- **Tables.** Each candidate is one table of the wave, and every seat of it plays the candidate's `faction-search`
  parameters, which act only on the seat that plays the hunted faction. Each game therefore has the hunted faction at a
  seat against `faction-spread` play, and the case matrix is limited to the faction sets that hold it.
- **The plan.** Round 0 plays 64 candidates on 32 games, round 1 the best 16 on 128 and round 2 the best 4 on 300; the
  four are the short list. A seed plays one game per rotation of every faction set that holds the faction (5 at five
  players, 16 at four, 18 at three), and a round plays the smallest whole number of seeds that gives at least its games, so
  five players play 35, 130 and 300 games and the seeds extend from round to round. The objective is the faction's win
  ratio, its win rate over the fair share `1 / playerCount`.
- **The gate.** When the search ends, the cell is confirmed only if the best candidate's ratio over its 300 games is at
  least 1.25. Otherwise the campaign is `COMPLETED` at once with a report whose `classification` is `NOT_CONFIRMED`, and no
  confirmation game is played. A cell that passes stays `SEARCH_COMPLETED` until `POST .../confirmation`.
- **Confirmation.** One run on fresh seeds derived from `confirmationMasterSeed`, with two tables over the same seeds: the
  best candidate on every seat, and the standard `faction-spread` on every seat.
- **The report.** `huntReport` states the faction and table size, the realized screening noise (the standard deviation of
  the round-0 ratios), the share of round-0 candidates at or above 1.25 and, once confirmed, the candidate's ratio and its
  95% interval, the standard seat's ratio, the normal ratio (the larger of 1.0 and the standard seat's) and the
  parameters found. The cell is `EXPLOIT` at a ratio of at least 1.5 times the normal ratio, `FLAGGED` from 1.25 times
  (at a normal ratio of 1.0, flagged from 1.25 and exploit from 1.5; at 2.0, flagged from 2.5 and exploit from 3.0) and
  `BALANCE_NOISE` below that. A cell without a flag is evidence, not a guarantee, that no exploit exists, and results
  characterize bot strategies only: they say nothing about balance among human players.
- **Cost.** A five-player cell plays 5,296 games at most (64 x 32 + 16 x 128 + 4 x 300; the seeds extend, so the games
  actually played are fewer), about 26,500 for the five cells and 4.2 hours at about 6,300 games an hour, plus about
  0.1 hour per confirmed cell. See [the spike](spikes/search-bot.md).

**Defaults of a new campaign.** A definition may leave out `parameterSpaceVersion`, `criterionVersion`, `policies`,
`searchMasterSeed`, `confirmationMasterSeed` and `algorithm` (or any field of it). The service resolves each one when it
accepts the campaign and freezes the result, so the campaign always reports the values it ran with: the algorithm comes
from the newest `search-budgets/v*.json` record, the versions from the newest parameter space and balance criterion the
service serves, and each missing master seed is a random unsigned 64-bit number that differs from the other. A retry with
the same `Idempotency-Key` and the same body returns the same campaign, with the seeds it was given the first time.

The designer application has a **Campaigns** page that lists campaigns and starts one from a short form (name, rules
bundle, player counts and games at once; the versions, algorithm and master seeds sit in a collapsed **Advanced** block
and are sent only when filled in), and a campaign page with its state, active time spent, round, short list, the algorithm, versions and master seeds it runs with, and paged decision log with each decision's gate miss, pause,
resume and cancel enabled only where the state accepts them, and a control for the games at once. Once the search is complete the page offers **Confirm short list**; from
then on it shows a **Confirmation** report that states the seeds, the games and the tables played and has one entry per
short-listed candidate (and one for the baseline only on a confirmation recorded when it still played one): the progress
of the run while it plays, then the verdict, every criterion line's verdict, the confirmation sample (seeds played against
seeds required and whether the full case matrix was played) and the statement that bot results say nothing about balance
among human players.
