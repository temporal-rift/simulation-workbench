# Evidence, replay, reports and comparisons

## Evidence, replay, and reproduction

### What a case retains

Every game a case plays is retained, including the ones that failed or were abandoned and replaced. Evidence is
never erased and is kept per game, so a failed attempt stays on record next to the game that counted. For each game:

| Evidence | Content |
|---|---|
| Steps | Every command a seat sent, in the order sent: the entitled observation it decided from, the canonical decision, the answer of the service (`ACCEPTED`, `REJECTED` with its code, `UNACKNOWLEDGED` until accepted state resolves it, `NOT_SPENT`), the logical time, and the policy entropy coordinates (stream, policy seed, seat, window, and the draw index, which counts the rejections that preceded it) |
| Raw events | Every event the game published on the `game.events` and `timeline.events` topics of its lane, with its headers (event type, identifier, aggregate, occurrence time, version) and the payload exactly as delivered |
| Source offsets | The next offset to read for each topic partition; it only moves forward |
| Pinned artifacts | The exact manifest the case ran under and, once the case succeeds, its accepted decision transcript and semantic digest |
| Reported rules digests | The rules digest game-service and timeline-service each reported in the checkpoint of the case's execution context, next to the effective rules digest of the case's variant |

Raw events are deduplicated by **source and event identifier**: a score or any other event delivered three times is
one logical event, so it can never inflate a count. The same identifier on another topic is another event. Partition
and offset locate a record inside its own source only; no order across topics is ever inferred or invented.

A case is complete only when the participant result, the scoring operation, and the published `GameEnded` event agree
on the end reason and every final score. If `GameEnded` or the final scores arrive late, the case stays running and is
read as incomplete; it is never recorded as a zero-score game.

Pinned artifacts live in a content-addressed store: each is addressed by the SHA-256 of its bytes and verified again on
every read. The store is protected: nothing in it is served directly, and only an authorized observer replay shows raw
events. Evidence never contains credentials.

### Replay

`GET /api/v1/runs/{runId}/cases/{caseId}/replay` pages through the evidence of the game that counts for the case: the
game of the succeeded attempt, or the latest game while the case is unfinished.

- `perspective=PLAYER&seatIndex=n` returns only the steps of that seat. Each step carries just the observation the seat
  was entitled to at that moment, so an opponent's hidden hand, hidden faction, and information the seat has not yet
  earned are absent by construction, and exact weights appear only from the step at which the seat earned them.
- `perspective=OBSERVER` additionally requires `simulation:observe` (`403 INSUFFICIENT_SCOPE` otherwise), forbids
  `seatIndex`, and shows the steps of every seat followed by the raw events (phase `EVENT`), listed per source in
  offset order. It is research data only and is never an input to a policy.
- A `PLAYER` request without a valid `seatIndex`, or an `OBSERVER` request with one, is `400
  INVALID_REPLAY_PERSPECTIVE`.

`step` is stable across pages. `afterStep` is the last step already read (omit it to start at the beginning), `limit`
caps the page, and `nextStep`, present when more remains, is the `afterStep` that continues the replay. Steps are
numbered from 0; a `PLAYER` replay skips the numbers that belong to other seats.

### Reproduction

`POST /api/v1/runs/{runId}/cases/{caseId}/reproductions` requests an exact reproduction of a succeeded case and returns
`202` with a `Reproduction` in state `QUEUED`. A worker then executes the pinned manifest and the accepted decision
transcript in a clean lane, deciding in every window exactly what the transcript accepted. The reproduction has its own
`reproductionId` and `attemptId`, keeps its own transcript and evidence, and never adds a research sample: the case keeps
its single result and its attempts unchanged. Repeating a request with the same `Idempotency-Key` returns the same
reproduction in whatever state it has reached, which is how its outcome is read; the same key for another case is
`409 IDEMPOTENCY_CONFLICT`.

| State | Meaning |
|---|---|
| `QUEUED`, `RUNNING` | Waiting for a free lane, or executing under a renewable lease. A restart or an expired lease queues it again |
| `MATCH` | The semantic setup, every accepted decision with the observation it was made from, the outcome, the scoring, and the result digest equal those of the saved case |
| `DIVERGED` | It ran and differed. `firstDivergence` carries the step of the saved case, the `kind`, and the `expected` and `actual` values |
| `FAILED` | It could not reach an ending (lane unavailable, timeout, contract or configuration mismatch); it says nothing about the case |

Divergence kinds: `OBSERVATION`, `DECISION`, and `WINDOW` (another seat or window at that step), `MISSING_STEP` and
`UNEXPECTED_STEP` (the reproduction stopped early or went on), `UNEXPECTED_WINDOW` (a window the transcript has no
decision for), `COMMAND_REJECTED` (the service now refuses a transcript decision; no other choice is substituted), and,
after the last step, `END_REASON`, `WINNERS`, `FINAL_SCORES`, `ERAS`, `SEMANTIC_DIGEST`. Only accepted commands are
compared: rejections and lost acknowledgements are transport history and do not change what the game accepted.
Transport identifiers, timestamps, and offsets are excluded from the comparison but stay inspectable in the evidence.

A reproduction is requested only against intact pinned artifacts. A case with no saved result or transcript, an artifact
that is missing or no longer matches its content address, or a frozen manifest that differs from the one the case ran
under returns `409 MANIFEST_MISMATCH` and creates nothing. A changed rules variant is a comparison, not an exact
reproduction.

A reproduction runs on the lane that played the case, because the bot identities of a lane are part of what the seats
observe and decide on. Only the workbench itself places one elsewhere, for a fidelity check, and only on a lane of the same
content, source revisions, contract versions and bot identities as the lane that played the case. It waits, queued, while that lane is busy, shares the worker pool, and is taken after pending
cases. It opens its own execution context, carrying the case's frozen rules, and closes it afterwards, as any case does. A
reproduction whose lane is no longer configured, or no longer fits the case (another content digest, service build or
contract version), stays queued and is never played on another lane. Known limitation: a lane keeps the state of a game
it played, so replaying the same case on it stalls (temporal-rift/simulation-workbench#95).

### Export

Evidence is exported through the replay operation: an authorized `OBSERVER` replay is the complete record of a game
(steps, then raw events with their source, partition, offset, and payload), paged and without credentials. Reports and
comparisons export separately (see "Reports and comparisons").

### Lane event topics

The observer reads each lane from its own event topics, `game.events` and `timeline.events` by default, using the
workbench Kafka client settings. A lane that publishes elsewhere sets them next to its URLs:

```yaml
workbench:
  execution:
    lanes:
      - id: lane-1
        game-events-topic: lane-1.game.events
        timeline-events-topic: lane-1.timeline.events
        kafka-bootstrap-servers: lane-1-kafka:9092
```

## Reports and comparisons

`GET /api/v1/runs/{runId}/report` (`simulation:read`) returns a run's report as JSON, or as CSV with
`Accept: text/csv`. `POST /api/v1/comparisons` (`simulation:write`, `Idempotency-Key`) compares two variants, of one
run or of two runs, and `GET /api/v1/comparisons/{comparisonId}` reads the comparison as JSON or CSV. Every number
is computed by analysis version `5` with analysis seed `1` (a comparison keeps the version it was created under); both
are on every report, comparison, and CSV row, and the same version, seed, and case results always produce
byte-identical documents. Analysis version `2` is version `1` plus the `failure_share` metric, version `3` is
version `2` plus the balance diagnostics below, version `4` is version `3` plus the bet survival metrics below, and
version `5` is version `4` plus the win condition metric below: every earlier metric keeps its value and interval.

### Populations

Only succeeded cases are games, each counted once however many attempts it took; reproductions never add a game.
Pending, running, failed, and cancelled cases appear in the case counts and never in a denominator, and a report is
`complete` only when every requested case succeeded. An ending without winners is still a game.

For every variant, policy, and player count the report holds four cohort levels, in this order:

| Level | `factionSet` | `seatIndex` | Seat-games counted |
|---|---|---|---|
| Pooled | `null` | `null` | every seat |
| Per seat | `null` | seat | that seat |
| Per faction set | set (matrix order) | `null` | every seat |
| Per faction set and seat | set | seat | that seat |

Game metrics appear only in cohorts that pool seats.

### Metrics of analysis version 1

Each metric names its `statistic`: `RATE` is numerator over denominator, `MEAN` is a total over its sample size, and
`QUANTILE` is the smallest observed value whose cumulative share reaches the quantile (0.1, 0.5, 0.9). A metric with
nothing in its denominator is `NO_DATA`.

| Metric | Statistic | Dimensions | Formula |
|---|---|---|---|
| `ending_cause_rate` | RATE | `endReason` (all six) | games ending that way / games |
| `no_winner_rate`, `shared_win_rate` | RATE | | games with no winner, or with two or more winners / games |
| `eras`, `rounds`, `decisions` | MEAN, QUANTILE | `quantile` | per game |
| `paradox_findings` | MEAN | none, or `paradoxType` | findings per game |
| `paradoxes_resolved`, `paradoxes_cascaded` | MEAN | | resolved or cascaded findings per game |
| `distinct_cascaded_events` | MEAN | | distinct events cascaded per game |
| `paradox_resolution_rate`, `paradox_cascade_rate` | RATE | | resolved or cascaded findings / findings |
| `faction_win_rate` | RATE | `faction` | seat-games of the faction among the winners / seat-games of the faction |
| `faction_shared_win_rate` | RATE | `faction` | such wins in games with two or more winners / seat-games of the faction |
| `score` | MEAN, QUANTILE | `faction`, `quantile` | final score of the faction's seat-games |
| `cards_offered`, `cards_kept`, `cards_played` | MEAN | `cardType`, `cardGrade` | cards dealt, kept, or played in action rounds per seat-game |
| `reactive_cards_offered`, `reactive_cards_played` | MEAN | `cardType`, `cardGrade` | paradox-resolution cards offered or played per seat-game |
| `card_keep_rate` | RATE | `cardType`, `cardGrade` | kept / offered |
| `card_playable_rate` | RATE | `cardType`, `cardGrade` | playable card-rounds / card-rounds of known playability |
| `card_play_rate_when_playable` | RATE | `cardType`, `cardGrade` | action-round plays / playable card-rounds |
| `special_attempts`, `special_submission_rejections` | MEAN | `specialAction` | submissions, and those the service refused, per seat-game |
| `special_accepts`, `special_resolution_rejections` | MEAN | `specialAction` | published plays, and accepted specials rejected at resolution, per seat-game |
| `declaration_offers` | MEAN | | declaration windows offered per seat-game |
| `declarations` | MEAN | `specialAction` | declarations in that mode per seat-game |
| `declaration_rate` | RATE | | declarations / offers |

A shared win counts as a win for every winner, so faction win rates need not sum to one: Erasers present in 10 games
and winning 4, one of them shared with Weavers, have a win rate of 0.4 and a shared-win rate of 0.1, and the shared
game is also a win for Weavers. A paradox finding and a cascaded event are different units: three findings, two of
which cascade on the same event, are 3 findings, 2 cascaded findings, and 1 distinct cascaded event.

A card-round is a card the seat held at the start of an action round. Its playability is known when the seat observed
that round, because the retained observation holds the whole hand with each card's playability; it is unknown when
the seat did not (for example while jammed), and those card-rounds are left out of both playability rates and
reported as `unknownCount`. A seat that held SCAN II for four rounds, observed three with the card playable in two,
and was jammed in the fourth contributes 2 of 3 to `card_playable_rate` and 1 to its unknown count. Factions are listed
for every faction of the run's faction sets at that player count; card, special, declaration, and paradox values are
listed only when they occur somewhere in the run.

### Failure share (analysis version 2)

`failure_share` (statistic `RATE`, unit `cases`) is the share of finished cases that failed: cases whose final state is
`FAILED` over cases whose final state is `SUCCEEDED` or `FAILED`. It exists because a failed case leaves every game
metric, so a configuration that stalls games would otherwise look healthy. A case counts under the code of its final
failed attempt; a case that failed and then succeeded on a retry counts only in the denominator, and cancelled,
pending, and running cases count in neither. It appears in the cohorts that pool seats (pooled and per faction set),
once without `failureCode` (every code) and once for each failure code that occurs anywhere in the run. It is resampled
over the seeds that have finished cases, so a seed whose cases all failed counts there; game metrics keep resampling the
seeds that have games, so their values and intervals do not depend on the analysis version.

### Uncertainty

Services derive every gameplay draw from the case seed, so cases sharing a seed share their deck order and deals
across faction sets, seat rotations, and variants. A seed is therefore one independent block, and `independentBlocks`
counts the seeds behind a cohort. Intervals are 95% percentile intervals over 2,000 resamples of the cohort's seeds
with replacement (`BLOCK_BOOTSTRAP`); every metric of a cohort is recomputed under the same resamples. A cohort with
fewer than two seeds reports `INSUFFICIENT_SAMPLE` with its value and no interval: one seed's ten faction sets and
their rotations are one observation, not thirty.

### Comparisons

Each side names a run and one of its variants. The two frozen experiments must be equal in services, contracts,
policies and their parameters, seeds, player counts, faction sets, seat rotation, timing, and rejection budget;
otherwise creation answers `422 INCOMPARABLE_RUNS` naming every difference. Concurrency and the case wall timeout
may differ. `declaredDifferences` lists rules and content whose effective digests differ between the two variants.
An unknown run is `404 RESOURCE_NOT_FOUND`; an unknown variant, the same side twice, or a malformed request is
`400 INVALID_COMPARISON`; a reused key with another request is `409 IDEMPOTENCY_CONFLICT`.

A baseline and a candidate case pair when they share seed, player count, policy, and seat-to-faction assignment. In
each cohort a seed is matched only when every pair of the cohort in that seed succeeded on both sides; only matched
seeds enter the estimates, so excluding a failed pair never unbalances seats or factions. Each metric is reported for
both sides over the matched seeds, with their difference (candidate minus baseline) and a 95% block-bootstrap
interval of the difference that resamples matched seeds identically on both sides. Every pair with a failed or
cancelled side (`FAILED_COUNTERPART`) or an unfinished side (`UNFINISHED`) is listed in `excludedPairs`, and a
comparison stays incomplete while any pair has not succeeded. A comparison is stored as its definition and recomputed
from current results on every read.

### Balance criterion and run evaluation

The balance criterion is one read-only document served from configuration: its tables (a policy for each seat, and
the phases, `SCREENING` or `CONFIRMATION`, that play it), its lines (a metric, a dimension, the populations and player
counts it covers, a band, an interval rule, a role as `GATE` or `SCORED`, a weight), its aggregation, and its evidence
rule. A population is the seats of one table played by one policy. `GET /api/v1/balance-criteria` lists it and `GET /api/v1/balance-criteria/{version}`
reads it (its `version` is the revision of its content, and any other version is `404`); its `digest` is the SHA-256 of its canonical serialisation (object keys sorted, no whitespace, numbers by value
without trailing zeros), so any change of any value changes it. A version whose evidence rule the evaluator cannot apply
(another confidence level, other invalidating causes, another aggregation), a population naming a policy no seat of
its table plays, or the exact interval on a line that is not a share of games or cases is refused at startup. The criterion is `balance-criteria/balance-criterion.json` in the application's configuration, never edited through
the API; its decision record is `docs/balance-criteria/current.md`. It has two uniform tables, `faction-greedy` and
`random`, and the confirmation-only table `random-seat-vs-faction-greedy` (seat 0 `random`, the other seats
`faction-greedy`), measures G-07 on its random seat, widens the interval of its share lines with a non-zero limit,
and needs 104 confirmation seeds. A criterion names a policy by its id only (`faction-greedy`, `random`) and resolves
it to the one bundle of that id, so it cannot disagree with the bot a run played. Evaluating a run whose tables the
criterion does not list is refused with `422 UNKNOWN_CRITERION_TABLES`, naming them, instead of returning lines with
no data.

`GET /api/v1/runs/{runId}/evaluation?variantLabel=&criterionVersion=&mode=` (`simulation:read`) evaluates one variant of
a run against one criterion version. Nothing is stored: it is derived from the run's current cases on every read, with
the analysis seed, and the same run, variant, version, and mode always return byte-identical JSON. Lines are ordered by
line id, table, policy, player count, faction set, and faction.

- Each criterion line expands into one evaluated line per population, cohort and dimension value it covers. A cohort
  belongs to a criterion table when its table maps to it and each seat plays the policy the table names for that seat.
  A seat metric counts only the population's seats; a game metric and `failure_share` count every game or case. A
  faction ratio is the faction's win rate on the population's seats over `R`, the winner seat-games over the
  seat-games of every seat of the same cohort; the sum of failure codes is the sum of those `failure_share` codes. Both
  are recomputed in every resample of their cohort, so a line's interval is the interval of the derived value and
  never a combination of separate intervals. A cohort without a winner has no `R`, and its ratio lines are `NO_DATA`.
- A line whose interval rule is `BLOCK_BOOTSTRAP_UNION_CLOPPER_PEARSON` widens its bootstrap interval to the union with
  the 95% two-sided Clopper–Pearson interval on the cohort's pooled numerator and denominator, so a share without
  events is not shown to be below a limit on a few games.
- A pooled line whose population has no cohort at a player count the variant played is listed as `NO_DATA`, one line
  per dimension value. A screening evaluation therefore lists G-07 without data; it never rejects.
- Deviation is 0 inside the band and otherwise the distance to the nearer bound over the band width (or, for a
  one-sided limit, over its scale). A line is `MEETS` when its whole interval lies inside the band, `FAILS` when it lies
  wholly outside, and `INCONCLUSIVE` otherwise, and always `INCONCLUSIVE` without an interval. The score is the sum of
  `weight × deviation²` over the scored lines, from point values; it ranks candidates and never rejects or confirms.
- Validity comes first. The evaluation is `INVALID`, with every applicable reason and no score, when a case of the
  variant did not finish (`UNFINISHED_CASES`), any case failed with `CONTRACT_MISMATCH` or `CONFIGURATION_DRIFT`, the
  `EXECUTION_FAILED` share of a table and player count is above the criterion's limit (`EXECUTION_FAILED_SHARE`), or
  a game ended with every player abandoned (`ALL_PLAYERS_ABANDONED`).
- A valid evaluation is `REJECTED` when a gate fails. Only in `CONFIRMATION` mode is it `CONFIRMED`, and only with a
  satisfied confirmation sample (at least the criterion's minimum seeds, and every faction set at 3, 4, and 5 players
  under every cyclic rotation on every table of the criterion's `CONFIRMATION` phase, counting only cases whose seats
  play what the table names), every pooled gate meeting its band, and no scored
  line failing. Otherwise it is `UNCONFIRMED`; `SCREENING` mode never confirms.

Bot results say nothing about balance among human players, and an evaluation must not be presented as if they did.

### Balance diagnostics (analysis version 3)

Version `3` measures where a faction's strength comes from, so a balance change can be judged by its cause and not only
by its win rate. It adds these metrics, each only to the cohorts that pool seats unless it is a seat metric:

- `score_by_reason` (statistic `MEAN`, unit points per seat-game; dimensions `faction` and `scoreReason`): the signed
  points a faction's seat records for one score reason, such as `ANNIHILATED_OUTCOME` or `PARADOX_CASCADE_PENALTY`.
  A reason is listed for a faction only when some seat of it recorded that reason in the run.
- `win_path_rate` (statistic `RATE`, unit wins; dimensions `faction` and `winPath`): the share of a faction's winning
  seat-games won by each path: `FACTION_OBJECTIVE`, `SCORE_THRESHOLD`, `LAST_PLAYER_STANDING`, or, for the two special
  endings, `TIMELINE_STABILIZED` and `TIMELINE_COLLAPSED`.
- `win_era` (statistic `MEAN`, unit eras; dimension `faction`): the era a faction's winners won in.
- `bet_success_rate` (statistic `RATE`, unit settled bets; dimension `faction`): how often a faction's signature bet was
  kept, counted from the score reasons that settle it: Annihilate, a written outcome, a secret outcome, a chain link
  and a declared outcome each settle as kept or lost.
- `paradoxes_cascaded` by `paradoxType` (statistic `MEAN`, unit findings per game): cascaded findings of each paradox
  type, beside the existing all-types metric.

Facts for version `3` are stored under facts version `2`; versions `1` and `2` keep reading facts version `1`, so
reports and comparisons made under an earlier version are unchanged.

### Bet survival (analysis version 4)

`bet_success_rate` counts only bets that reached scoring, so it can look alike for every faction while most bets were
voided or expired before scoring. Version `4` counts the bets directly, per faction and per seat-era (one era of one
seat is one unit, however many times the faction acted in it):

- A bet is **placed** when a Weaver's `THREAD` was accepted, a Revisionist's `REWRITE` was accepted, a Prophet's
  `FORESIGHT` that named an outcome was accepted, an Activist's Rally or Momentum declaration was recorded, or an
  Eraser's `ANNIHILATE` was accepted.
- A placed bet is **scored** when the seat's score record of that era lists a win or a miss reason of that bet:
  `CHAIN_LINK_ADDED` or `CHAIN_LINK_MISSED`; `SECRET_OUTCOME_WON` or `SECRET_OUTCOME_MISSED`;
  `EVENT_RESOLVED_AS_WRITTEN`, `FULFILLMENT_SUCCEEDED` or `EVENT_RESOLVED_DIFFERENTLY_THAN_WRITTEN`;
  `DECLARED_OUTCOME_WON[_WITH_RALLY]` or `DECLARED_OUTCOME_MISSED[_WITH_RALLY]`; `ANNIHILATED_OUTCOME`, or
  `ANNIHILATION_WASTED` (a wasted Annihilate is a lost bet, counted whenever it is listed, whatever its points). Every reason of a joined record counts. It is **won** when the
  reason is a win.
- `bets_placed` and `bets_scored` (statistic `MEAN`, unit bets per seat-game; dimension `faction`): the faction's
  placed and scored bets per seat-game.
- `bet_survival_rate` (statistic `RATE`, unit placed bets; dimension `faction`): scored over placed.
- `bet_win_rate_per_era` (statistic `RATE`, unit seat-eras; dimension `faction`): won bets over the eras the faction's
  seat-games played.

A scored bet needs a placed bet in the same era, so survival never exceeds 1. The scoring contract lists
`ANNIHILATION_WASTED` whenever a creditable Annihilate did not change the winner, so the points setting never changes
the count; an Annihilate with neither Eraser reason listed (protected by a writing) is placed and not scored. The metrics use only the `faction` dimension, so the CSV exports keep their
layouts (report format version `4`, comparison format version `3`) and carry them as extra rows.

Facts for version `4` are stored under facts version `3`; versions `1` and `2` keep reading facts version `1` and
version `3` keeps reading facts version `2`, so reports and comparisons made under an earlier version are unchanged.

### Wins by every condition met (analysis version 5)

`win_path_rate` reads one path per winner, so a winner that reached the score threshold and met its objective at once
is counted as `SCORE_THRESHOLD` only. Each winner of a case result now lists every condition it met (from the game's
projection; when the game lists none, `[winType]` for a score or objective win and nothing otherwise). Version `5` adds:

- `win_condition_rate` (statistic `RATE`, unit wins; dimensions `faction` and `win_path`, which carries the
  condition): the share of the faction's wins whose winner lists `SCORE_THRESHOLD` or `FACTION_OBJECTIVE`. A winner
  counts once toward each condition it lists, so a faction's two shares may sum to more than 1. A winner that lists no
  condition (a special ending) stays in the denominator and counts toward neither.

`win_path_rate` keeps its definition. The CSV exports keep their layouts and carry the metric as extra rows. Facts for
version `5` are stored under facts version `4`; earlier versions keep reading what they read, so reports and
comparisons made under them are unchanged. A case result stored before winners listed their conditions reads them
through the same fallback.

### Exports

CSV exports have a header row and repeat the full attribution on every row: the format version, the run or
comparison, the analysis version and seed, completeness, the cohort, its counts, the metric with its statistic and
dimensions, and the values. Empty fields are null values. The report export's `failureCode` column, after `quantile`,
is empty for every metric not split by failure code, including the all-codes `failure_share`, and holds the failure
code of a per-code `failure_share` row. The `scoreReason` and `winPath` columns follow it in the report export, and
follow `quantile` in the comparison export; they are empty for every metric not split by them. Report format version
`4` and comparison format version `3` carry these columns. A format version names the layout of the
document, not the analysis that computed it, and the API contract allows one layout at a time: a report or comparison
stored under an earlier analysis version keeps its analysis version and its numbers, and is read in the current layout. Exports carry no credentials or machine-local paths.

### Limitations

Results characterize the run's bot policies, not human balance, and are stratified by policy for that reason.
Card, special, and faction associations are observational: they describe how those policies played, not the
strength of a card or faction. Cases saved before action-round observations kept the whole hand hold the narrower
observation: their action rounds count as unobserved here, and reproducing them reports an `OBSERVATION`
divergence.
