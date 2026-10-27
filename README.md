# Temporal Rift Simulation Workbench

Designer product for reproducible Temporal Rift experiments: freeze an experiment, run it against real
services in isolated lanes, compare rules variants with honest statistics, inspect individual cases, and
reproduce a saved case. Gameplay stays authoritative in `game-service` and `timeline-service`; bots use the
same authenticated participant commands and entitled participant views as human players.

## Ownership

This repository owns experiment management, bot policies, durable execution, evidence, analytics, and the
designer UI. It does not own game rules or outcome resolution, event-sourced timeline state, read
projections, contract publication, lane provisioning, or the human player's client:

| Responsibility | Owner |
|---|---|
| Game sessions, actions, scoring rules | `game-service` |
| Weighted outcomes, resolution, paradox handling | `timeline-service` |
| Participant projections and history | `read-service` |
| Published contract modules | `apis` |
| Isolated runtime lanes and deployed verification | `infrastructure` |
| Human gameplay client | `game-client` |
| Experiment/run/policy/report/replay/reproduction behavior | this repository |

Research orchestration lives here, never in a gameplay service. Observer capabilities live here, never in
the human player's client.

## Boundaries

- All service boundaries use generated clients and interfaces from the published contract modules listed
  below. No hand-written copy of the game engine or player contract types may be introduced in this
  repository.
- Bot policies receive only the facts their participant is entitled to at that step. Raw observer evidence,
  opposing credentials, and unearned private data never become policy inputs.
- Operator controls (`simulation:control`) exist only in isolated simulation deployments. Designer access uses
  `simulation:read` / `simulation:write`; observer replay additionally requires `simulation:observe`.

## Requirements

- Java 26+
- Maven 4.0.0-rc-7+
- Docker (for Testcontainers and local infrastructure)
- A `github` server in `~/.m2/settings.xml` holding your GitHub username and a token with `read:packages`: shared
  artifacts resolve from GitHub Packages, which needs a token even for public packages. The registry itself is declared
  in `.mvn/settings.xml`. To build the image, pass the settings as a secret:
  `docker build --secret id=maven_settings,src=$HOME/.m2/settings.xml .`

## Build and test

```bash
# All tests (unit + integration via Testcontainers)
mvn test

# Full verification (also runs Failsafe *IT plus Sonar analysis with SONAR_TOKEN set)
mvn verify

# Check and fix formatting
mvn spotless:apply

# Java only: skip the designer application's Node download, install, build, checks, and the tests that serve it
mvn verify -Dskip.installnodenpm -Dskip.npm

# Run all quality gates (formatting + Checkstyle)
mvn validate
```

## Dependencies

- Parent BOM: `temporal-rift-bom` (Spotless, Checkstyle, OpenAPI generator)
- Published contract modules from `apis`, consumed only through generated clients and types, versions pinned as
  `<*.version>` properties in `pom.xml`:

| Module | Use |
|---|---|
| `simulation-api` | Workbench boundary: experiments, runs/cases, reports/comparisons, replay/reproduction |
| `simulation-control-api` | Isolated execution boundary: execution context, checkpoints, logical clock |
| `session-event`, `action-event`, `timeline-event`, `scoring-event` | Evidence and result attribution |
| `session-api`, `action-api`, `scoring-api`, `projection-api` | Authenticated participant play and each seat's own game state |

## Application layout

Spring Modulith application (`io.github.temporalrift.workbench`) with hexagonal modules:

| Module | Responsibility |
|---|---|
| `experiment` | Immutable experiment freeze, idempotent creation, deterministic matrix preview |
| `execution` | Durable real-service batches: runs, cases, attempts, leases, command reconciliation, cancel and resume; retained evidence, perspective-safe replay, and exact reproduction |
| `policy` | Versioned baseline bot policies |
| `analysis` | Run reports and variant comparisons: attributed metrics, seed-block uncertainty, JSON and CSV exports |

The designer web application lives in `ui/` (see "Designer web application"). `domain/` is plain Java, `application/` never depends on `infrastructure/`, and modules communicate
only via `ApplicationEvent`. All service boundaries are generated from the pinned contract modules;
no hand-written copy of engine or player-contract types may be introduced here.

## Authorization

OAuth2 resource server (JWT bearer). Reads require `simulation:read`; mutations require
`simulation:write`; observer replay additionally requires `simulation:observe`.

## Designer web application

`ui/` is a React, TypeScript and Vite application that the service serves on its own origin: `/` and its client-side
routes (`/experiments`, `/runs`, and deeper links to them) return the application to a browser, its built assets are
under `/assets/`, and everything it reads goes through the bearer-protected `/api/`, which never answers with the
application. The Maven build installs Node, installs the dependencies when `ui/package-lock.json` changed, generates the
client from the pinned contract, runs lint, type checking and the component and accessibility tests, and copies the
build into `static/` of the jar.

A designer signs in with the identity provider whose tokens the service accepts (authorization code with PKCE; tokens
are kept in memory only). The application offers each action according to the granted scopes and shows an action it
may not take as unavailable, naming the missing scope; the service remains the authority on every request.

| Property | Environment variable | Meaning |
|---|---|---|
| `workbench.ui.oidc-issuer-url` | `JWT_ISSUER_URI` | Issuer the browser signs in with; defaults to the issuer that validates bearer tokens |
| `workbench.ui.oidc-client-id` | `WORKBENCH_UI_OIDC_CLIENT_ID` | Public client identifier (default `simulation-workbench-ui`); its redirect URI is `<origin>/auth/callback` |
| `workbench.ui.oidc-audience` | `WORKBENCH_UI_OIDC_AUDIENCE` | Audience requested at sign-in, when the issuer needs one |

The browser reads this at `/config.js`. Responses carry a content security policy that lets the application load
only its own scripts and styles and call only this service and the issuer.

What a designer can do:

- Configure an experiment, optionally from a template (variants, each running a bundle chosen from those the lanes
  serve, catalog policies with their seats, seeds, player counts, faction sets, timing and execution bounds), preview the exact case matrix the service would freeze, and freeze it only after
  confirming the shown digest. An unsent draft is kept in the tab's session storage and survives an expired session.
- Open a frozen experiment, which is read-only, and start a new draft from its manifest.
- Launch a run and follow its state and counts while it is open; cancel while it is cancellable and resume from
  `INTERRUPTED`; read each failure with its code and message and open the failed case. Pending, running, failed and
  cancelled cases are never shown as results and incomplete data is marked partial.
- Read a run's report (every cohort with its denominators, independent blocks, intervals or the reason there is none,
  and unknown counts; marked partial while any requested case has not succeeded) and export its JSON or CSV exactly as
  the service returns it. Compare two variants and read the declared differences, matched blocks, excluded pairs and
  paired differences; runs the service refuses as incomparable are shown with the differences it names.
- Open a case with its seats, every attempt and failure, and its result or the plain statement that it has none.
- Inspect a case's replay from one perspective at a time (see "Perspectives" below), and request a reproduction of a
  saved case and follow it to a match or its first divergence.

For local work, `npm run dev` in `ui/` serves the application with hot reload and forwards `/api/` and `/config.js`
to a running service (`WORKBENCH_SERVICE_URL`, default `http://localhost:8080`). The typed client in
`ui/src/api/generated` is not versioned: the Maven build generates it with `npm run generate:api` from the contract
version pinned in `pom.xml` (`simulation-api.version`, unpacked by Maven into `target/dependency-specs`). After a
Maven build, or after bumping the pin, run `npm run generate:api` before `npm run dev`.

## Designer guide

A worked pass through the whole loop, from a configured experiment to a reproduced case. Each step names the page
and what to expect; the sections after it say what the service does underneath.

### Before you start

Bring up the dependencies the `dev` profile expects (PostgreSQL on 5432, Kafka on 9092, an OIDC issuer on 9000) and
start the service from this repository:

```bash
docker compose -f ../infrastructure/compose.yml up -d postgres kafka kafka-topics identity-provider
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

`spring-boot:run` builds the designer application and serves it from `static/`; with `-Dskip.npm` there is no
application to load at <http://localhost:8080>, only the API.

Open <http://localhost:8080> and choose **Sign in**. The local issuer accepts any username and grants the scopes the
application asks for, so everything below is available. Cases execute only on configured isolated lanes serving their
variant's rules and content (see "Operating the runner"); a run whose variants are not all served is refused when it is
started.

### 1. Configure and preview

**Experiments → New experiment.** Optionally **Start from** a template: *Baseline policy comparison* (one variant, the
random and faction-greedy policies each on every seat) or *Rules variant comparison* (two variants, the random policy on
every seat). A template fills the variant slots with the bundles the lanes serve, in order, and sets seeds `42`, player
counts 3, 4 and 5, every faction set, and the execution bounds; every value stays editable and your experiment name is
kept. Then name the experiment, label each variant and choose its **Bundle** from those the configured lanes serve. The
bundle's artifacts and effective digests, and the service images and contract versions it pins, are shown read-only:
nothing that identifies a bundle is typed. Variants are comparable only when their bundles pin the same services and
contracts; otherwise the page names the differing fields and the draft cannot be previewed or frozen. Pick bot policies
from the catalog and the seats each one plays, and set seeds, player counts, faction sets and the execution bounds. If
no lane is configured the page says so and nothing can be frozen. A value the service refuses is marked on its field,
and your unsent draft survives a reload and an expired session.

Press **Preview matrix**. The page shows the exact case count, the count per variant and player count, and a browsable
page of case coordinates, all as the service computed them. Nothing is stored yet.

### 2. Freeze

Press **Freeze this experiment…**, read the digest and case count in the confirmation, and press **Confirm and
freeze**. A frozen experiment is read-only; **Change: new draft from this experiment** starts a new draft from its
manifest and leaves the original untouched.

### 3. Launch and follow a run

On the frozen experiment press **Launch a run**. The run page shows its state and counts (requested, pending, running,
succeeded, failed, cancelled) and refreshes while the run is open and the tab is visible. Cancel is offered while the
run can still be stopped and resume only from `INTERRUPTED`. Failed cases are listed with their code and message and
link to the case. Anything that has not succeeded is marked **Partial** and is never counted as a result.

### 4. Read the report

Open **Report** on the run. Each cohort (variant, policy, player count, faction set, seat) shows its eligible games and
independent blocks, then every metric with its value, numerator and denominator, and its interval. A metric supported
by fewer than two independent blocks shows its value with **Insufficient sample** and no interval; a metric with a
denominator of 0 shows **No data**; opportunities whose playability is unknown are counted beside it, not in it.
**Export JSON** and **Export CSV** download the bytes the service returned, unchanged.

#### Evaluate a variant

The **Evaluation** panel on the run page judges one variant against a balance criterion version. Choose the variant, the
criterion version, and the mode (screening ranks and discards candidates; confirmation may confirm). The panel shows the
verdict, why a run is not valid evidence when it is not, the score, and in confirmation mode how much of the
confirmation sample the run has. The table lists every evaluated line with its cohort, value, interval, band,
deviation, and verdict, and can be filtered to failing or inconclusive lines. The panel always states that bot results
say nothing about balance among human players.

### 5. Compare two variants

**Comparisons → Compare two variants.** Enter the run and variant label of the baseline and of the candidate (a run
can be both sides when it holds both variants). The comparison lists the definitions the two sides declare as
different, the matched blocks, the pairs it had to exclude (a side failed or is unfinished) and each paired
difference with its interval. If the sides differ in anything that changes gameplay beyond the declared variant, the
service refuses the comparison and the page names the differences.

### 6. Inspect a case

From the run page open a case. It shows the seats with their faction and policy, every attempt with its state, game and
failure, and the result (end reason, winners, final scores, eras, rounds, decisions and semantic digest) or states that
the case has none.

#### Perspectives

**Replay** shows a case from one perspective at a time:

- **View as player** shows only the steps of the chosen seat and only what that seat was entitled to know at each step:
  never an opponent's hand or faction, and exact outcome weights only from the step at which the seat earned them. Step
  numbers belong to the whole case, so a seat's steps leave gaps where other seats acted. **Later steps** and
  **Earlier steps** page through the case.
- **View as observer…** needs the `simulation:observe` scope. It is labelled **Observer evidence**: every seat's steps,
  then the raw events, one list per source in the order that source delivered them (no order across sources exists).
  Without the scope the page says observer access was denied and shows nothing. Observer evidence is for inspection
  only and never feeds a bot policy.

Choosing another seat or switching perspective discards the previous view's data from the page and from the
application's cache before anything else is requested.

### 7. Reproduce a case

On the case page press **Reproduce this case**. A reproduction is a check of the saved case, never a new sample.

| You see | It means |
|---|---|
| Waiting, queued behind other work | The lane that played the case is busy or serves other rules or content; the reproduction starts when it is free and serving this case's variant again. This is not a failure. |
| Running | The case is being replayed against the pinned services. |
| Match | Every accepted decision and the final result equal the recording. |
| Diverged at step *n*, kind *k* | The first difference, with the expected (recorded) and actual values. Kinds are `OBSERVATION`, `DECISION`, `WINDOW`, `MISSING_STEP`, `UNEXPECTED_STEP`, `UNEXPECTED_WINDOW`, `COMMAND_REJECTED` and, after the last step, `END_REASON`, `WINNERS`, `FINAL_SCORES`, `ERAS`, `SEMANTIC_DIGEST`. |
| The reproduction failed | It could not be completed this time. This says nothing about the case; **Try again** requests a new one. |
| Cannot be reproduced | The case has no saved result or transcript, or its pinned configuration no longer matches (`409 MANIFEST_MISMATCH`). |

### When something goes wrong

Every refusal is shown as a plain sentence with a way to continue. If the service cannot be reached, the last data stays
on screen marked stale with **Retry**, and nothing you entered is lost. A refused action names the scope your session
lacks. Progress, errors and denials are announced to screen readers, and every control is operable from the keyboard.

## Experiments

`POST /api/v1/experiments` with an `Idempotency-Key` UUID header freezes a complete
`ExperimentManifest` and returns `201 Experiment` (`experimentId`, `manifest`, `manifestDigest`
SHA-256 hex, `createdAt`). The digest is computed over a canonical form of the manifest, so only a
meaning change (for example score threshold 20 vs 22) changes it — never key order or whitespace.
Experiments are immutable after creation: a configuration change creates a new experiment.

Idempotency: repeating a key with a byte-identical body returns the original experiment; reusing a
key with a changed body returns `409 IDEMPOTENCY_CONFLICT`.

A refusal that is attributable to one manifest value also carries `field`, its path in the manifest (for example
`seeds[1]` or `policies[0].seatAssignment[2].seatIndex`), so a client can place the problem on the input that caused it.

Error codes (RFC 9457 problem details): `400 INVALID_EXPERIMENT` (structural validation, including
duplicate factions, bad counts/seeds, credentials or machine-local paths), `409 MANIFEST_MISMATCH`
(malformed artifact references, image digests, source revisions, or contract pins),
`409 EXPERIMENT_IMMUTABLE` (any edit to a launched manifest), `409 IDEMPOTENCY_CONFLICT`,
`401 UNAUTHORIZED`, `403 INSUFFICIENT_SCOPE`, `404 RESOURCE_NOT_FOUND`.

Retention: the frozen manifest is persisted with its full configuration/content/policy artifacts,
seeds, faction sets, rotations, and execution bounds — never digests alone, and never credentials
or machine-local paths. Every case pins its exact manifest as evidence (see "Evidence, replay, and
reproduction"). The baseline rules document of each variant is retained with the experiment, so its
effective rules can always be read back; other resolved external bytes (content bundles, policy artifacts,
service images) are not retained by the runner.

## Variants as parameter overrides

A variant is a baseline rules document plus overrides. `rulesArtifact` names the baseline, which some configured lane
publishes as its `rules-document` (an `EffectiveRules` document); the workbench reads every such document at startup
and refuses to start, naming the lane, when a document's effective rules digest differs from the lane's declared
`rules-digest`. `overrides` maps parameter keys to numbers and `parameterSpaceVersion` names the space they are
validated against.

Parameter space version 1 is configuration (`parameter-spaces/v1.json`, equal to `docs/parameter-space/v1.md`) and is
read with `listParameterSpaces` and `getParameterSpace`. Its constraints are expressions in a small notation (comparison
chains over `+`, `-`, `*`, `max` and `and`; a subtraction needs spaces around `-` because keys contain it) that the
workbench both serves and evaluates.

Budget record version 1 ([docs/budget/v1.md](docs/budget/v1.md)) computes, from the measured capacity, the search
parameters that fit the default budget of 4 active hours on the simulator and the duration of the confirmation. Its
search parameters are configuration (`search-budgets/v1.json`) and the default `SearchAlgorithm` values of a new campaign.

Materialising a variant is a pure function of the baseline document, the space and the overrides: every key must be a
parameter, every value in range and on its step; overrides equal to the baseline are dropped; the rest replace the
baseline values and each derived key is set from its source; every constraint is then evaluated, and the effective rules
digest is the SHA-256 of the RFC 8785 serialisation of the properties. The first refusal is reported by key in ascending
order, then constraint id, as `400 INVALID_EXPERIMENT` (or `INVALID_REQUEST` on a preview) with `field`, `reason`
(`UNKNOWN_PARAMETER_SPACE`, `UNKNOWN_BASELINE`, `UNKNOWN_PARAMETER`, `OUT_OF_RANGE`, `OFF_STEP`,
`CONSTRAINT_VIOLATED`) and, for a constraint, `constraintId`.

`POST /api/v1/variant-rules-previews` (`simulation:read`, persists nothing) answers the rules, their digest and the
normalised overrides. Freezing (and previewing) an experiment normalises each variant the same way, sorts its overrides,
and refuses an `effectiveRulesDigest` that differs from the computed one (`409 MANIFEST_MISMATCH`) whenever the
baseline is published; a variant with overrides whose baseline no lane publishes is `UNKNOWN_BASELINE`. The baseline
document of every such variant is retained with the experiment, and `GET /api/v1/experiments/{experimentId}/variants/{label}/rules`
serves the frozen `EffectiveRules`. A case still runs only on a lane whose served digest equals its variant's, so a
variant with overrides matches no lane until lanes adopt per-game rules.

## Cohort-matrix preview

`MatrixPreviewService` deterministically enumerates every case coordinate (seed, faction set, seat
rotation, policy assignment, variant) of a frozen manifest: complete faction sets (10 combinations
at 3 players, 5 at 4, 1 at 5) under `CYCLIC` rotation place every faction in every seat, giving
30 + 20 + 5 = 55 base cases per seed, policy, and variant — 220 cases per seed with two homogeneous
policies and two variants. Ordering is stable and every coordinate carries a deterministic `caseKey`;
the durable runner persists these keys without recomputation.

`POST /api/v1/experiment-previews` (`simulation:write`, no `Idempotency-Key`) previews a manifest without
freezing it: the same validation as `createExperiment`, then `manifestDigest`, `caseCount`, a `breakdown` by
variant and player count, and a page of `cases` (`limit` up to 500, default 100, and `offset`). The digest and
the case keys and order equal those of the experiment frozen from the same manifest and of any run started
from it. Nothing is stored.

## Discovery

Reads for finding and reopening what the workbench holds need `simulation:read`. Lists return `{ items, total }`
and take `limit` (1 to 500, default 50) and `offset`; a missing run for a case list is
`404 RESOURCE_NOT_FOUND`.

| Operation | Path | Order and filters |
|---|---|---|
| `getExperiment` | `GET /api/v1/experiments/{experimentId}` | The experiment exactly as frozen |
| `listExperiments` | `GET /api/v1/experiments` | Newest first |
| `listRuns` | `GET /api/v1/runs` | Newest first; `experimentId`, `state` |
| `listCases` | `GET /api/v1/runs/{runId}/cases` | Matrix order; `state`, `variantLabel` |
| `listComparisons` | `GET /api/v1/comparisons` | Newest first; `runId` (either side). Summaries only: `getComparison` computes the result |
| `listPolicies` | `GET /api/v1/policies` | The bundles a manifest may reference, with `id`, `version`, `artifactDigest`, `description` and `parameters` (none for the baselines) |
| `listLaneBundles` | `GET /api/v1/lane-bundles` | One bundle per distinct effective rules and content digests the configured lanes serve, busy or free, ordered by those digests: artifacts, effective digests, `services`, `contracts` and `laneCount`. No lane identifier, URL or credential |
| `listExperimentTemplates` | `GET /api/v1/experiment-templates` | `baseline-policy-comparison` and `rules-variant-comparison`, each with its `variantCount` and complete `defaults` for every manifest field that does not identify a bundle |

A case summary carries `caseId`, `caseKey`, `runId`, `variantLabel`, `seed`, `playerCount`, `state` and a
nullable `endReason` (present once the case has a result).

### Guided setup

The templates default seeds `["42"]`, player counts `[3, 4, 5]`, all 16 faction sets (10 at 3 players, 5 at 4, 1 at
5), `CYCLIC` rotation and `LOGICAL` time; their `concurrency` is the number of configured lanes, at least 1 and at most
64. Their two remaining bounds are configuration:

| Property | Default | Bound |
|---|---|---|
| `workbench.experiment.templates.case-wall-timeout-seconds` | 3600 | 1 to 86,400; the workbench refuses to start otherwise |
| `workbench.experiment.templates.max-rejected-candidates-per-window` | 10 | 1 to 1,000; the workbench refuses to start otherwise |

A client assembles the `ExperimentManifest` it previews and freezes from a template's defaults, the selected bundles
(each variant's artifacts and effective digests; the manifest's `services` and `contracts` from bundles that share them)
and the designer's edits. A list `limit` or `offset` outside its bounds is `400 INVALID_REQUEST`.

## Runs and durable execution

`POST /api/v1/experiments/{experimentId}/runs` (body `{}`, `Idempotency-Key` header) creates a `Run` over a
frozen experiment and returns `202` in state `QUEUED` with its counts. One logical case exists per matrix
coordinate and is persisted up front, so the totals are known before anything executes. A manifest may expand to at most 100,000 cases, with `concurrency` up to 64, `caseWallTimeoutSeconds` up to 86,400 and `maxRejectedCandidatesPerWindow` up to 1,000; larger ones are `INVALID_EXPERIMENT`. The same key returns the
original run; the same key for another experiment returns `409 IDEMPOTENCY_CONFLICT`. When any variant of the
experiment cannot be played, because no configured lane declares its `effectiveContentDigest` with the services and
contracts the experiment pins or because the variant's frozen rules are not retained with the experiment, the request
returns `409 MANIFEST_MISMATCH`, names every unserved variant label, and creates nothing.

| Operation | Behavior |
|---|---|
| `GET /api/v1/runs/{runId}` | State, timestamps, failure, and case counts (`requested`, `pending`, `running`, `succeeded`, `failed`, `cancelled`) |
| `GET /api/v1/runs/{runId}/cases/{caseId}` | The logical case, every attempt, and the reconciled result |
| `POST /api/v1/runs/{runId}/cancel` | `202` on the first accepted cancel, `200` once the run is already terminal |
| `POST /api/v1/runs/{runId}/resume` | `202`, legal only from `INTERRUPTED`; any other state returns `409 INVALID_RUN_STATE` |
| `GET /api/v1/runs/{runId}/cases/{caseId}/replay` | One page of the retained evidence of a case from a player's or the observer's perspective |
| `POST /api/v1/runs/{runId}/cases/{caseId}/reproductions` | `202`; executes the pinned case again in a clean lane (see below) |


### States

```
QUEUED ──▶ RUNNING ──▶ COMPLETED          a run with failed cases still COMPLETES; FAILED means its frozen experiment is gone
   ▲          │
   │      INTERRUPTED ◀── process stop, crash, or an expired lease
   └─ resume ─┘
QUEUED | RUNNING | INTERRUPTED ──▶ CANCELLING ──▶ CANCELLED
```

Case states are `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, and `CANCELLED`. An attempt is one execution or
recovery of a case (`RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `INTERRUPTED`). A logical case is counted once
and holds at most one result, however many attempts recovered it; failed and interrupted attempts stay visible on
the case.

### What a case result is

A case succeeds when its game reached an authoritative ending and the ending, winners, faction reveal, and final
scores agree across every seat's own view and the scoring operation. Until all of that is reconciled the case stays
running; it is never recorded with partial or zero scores.

An ending without winners (`RESOLUTION_FAILED`, `ALL_PLAYERS_ABANDONED`, `DECK_EXHAUSTED`) is an authoritative game
outcome: the case succeeds with that `endReason`, no winners, and its final scores. It is not a failure. For
`TIMELINE_COLLAPSED` and `TIMELINE_STABILIZED` the winners carry no `winType`, because the participant contract
assigns one only to a normal victory.

Attempt failures are recorded, never turned into game samples:

| Code | Meaning | Retried |
|---|---|---|
| `RUNNER_TIMEOUT` | The case did not reach an authoritative ending within `caseWallTimeoutSeconds` | yes, up to `max-attempts-per-case` |
| `EXECUTION_FAILED` | A lane or service call failed in a way no reconciliation resolved | yes |
| `CONTRACT_MISMATCH` | A response or policy reference does not match the pinned contracts | no |
| `CONFIGURATION_DRIFT` | A service reports another case, manifest or rules digest than the one configured, or reveals another faction than planned | no |
| `POLICY_EXHAUSTED` | A seat had no candidate, pass, or decline left | no |

### Leases, recovery, and reconciliation

Pending cases are the jobs. A claim opens a new attempt that owns the case through a lease, renewed on every step
of the game; every outcome is written only by the lease owner of a still-running attempt. A worker whose attempt was
interrupted or recovered can therefore never overwrite the case, and a run's concurrency bound holds across
workers because claims on one run are serialized.

On start, the process interrupts every run a previous process left running; a graceful stop does the same for its
own attempts; a lease that expires without renewal interrupts its run. Completed results are never touched.
`resumeRun` re-queues the run, and only the unfinished cases are scheduled. A recovering attempt starts a new game in a
new execution context on a lane whose context of the interrupted attempt is closed, and the abandoned game's decisions
are forgotten.

Every decision a seat sends is recorded in the command ledger before it is sent, one slot per case, seat, and
window. A slot that was sent and never acknowledged is in doubt: it is reconciled against the seat's own accepted
state once the game has settled (the services have drained and every consumer group has applied the records the
observer read), and it is sent again only when
accepted state shows it was not spent. The accepted slots are the case's decision transcript, and the case's
`semanticDigest` covers the setup, that transcript, and the authoritative ending, so a recovered case digests like
an uninterrupted one. Starting a game is reconciled against the lobby's start state the same way.

Cancelling cancels pending cases immediately, signals running attempts through the run state (each stops at its
next step and is recorded as cancelled), and keeps every completed result. Repeating a cancel never reopens a
terminal run.

### Operating the runner

The runner needs isolated lanes. A lane is an independent deployment of `game-service`, `timeline-service`, and
`read-service` with the simulation controls enabled, one operator credential holding `simulation:control`, and
one bot identity per seat (at least five for the largest case). It hosts one case at a time and must be clean when
a case starts. Each case plays in an execution context of its own, opened on the lane's game and timeline services and
closed when the case ends, so a lane plays any number of cases in sequence without restarting; a lane that declares a
reset hook is recovered through it only when a context cannot be closed. Lanes and credentials are runtime
configuration only; they are never stored with experiments, runs, or evidence:

```yaml
workbench:
  execution:
    worker-enabled: true        # false for an API-only instance
    worker-threads: 2           # defaults to one per lane
    lease: 60s
    heartbeat: 10s
    max-attempts-per-case: 2
    reset-timeout: 5m           # bounds one context close and one call of a lane's reset hook
    lanes:
      - id: lane-1
        game-service-url: https://lane-1.game.internal
        timeline-service-url: https://lane-1.timeline.internal
        read-service-url: https://lane-1.read.internal
        operator-token: ${LANE_1_OPERATOR_TOKEN}
        rules-digest: 3f1c…        # effective rules digest of the bundle this lane serves
        rules-document: /etc/workbench/lanes/lane-1/effective-rules.json   # the lane baseline EffectiveRules document
        content-digest: 9b07…      # effective content digest of the bundle this lane serves
        rules-artifact-digest: 5d2a…     # the rules artifact the effective rules digest was resolved from
        rules-artifact-name: threshold-22   # optional
        content-artifact-digest: 0c8e…   # the content artifact the effective content digest was resolved from
        content-artifact-name: base-content   # optional
        services:
          game-service: { image-digest: "sha256:4e1f…", source-revision: 1a2b3c4 }
          timeline-service: { image-digest: "sha256:77aa…", source-revision: 1a2b3c4 }
          read-service: { image-digest: "sha256:c0de…", source-revision: 9f8e7d6 }
        contracts:                 # exact version of every contract module the lane's services implement
          session-api: 3.0.0
          action-api: 7.1.0
        reset-url: https://lanes.internal/lanes/lane-1/reset   # optional
        bots:
          - { player-id: 8a0c…, token: ${LANE_1_BOT_0_TOKEN} }
```

Each lane declares the frozen bundle it serves, as published by the deployment that provisioned it: its effective rules
and content digests, the rules and content artifact digests they were resolved from (with optional names), the image
digest and source revision of each of its three services, and the exact version of each contract module they
implement. The values follow the manifest's formats: digests are 64-character lowercase SHA-256 hex strings, image
digests `sha256:` followed by one, source revisions 7 to 64 lowercase hex characters, and contract versions exact
semantic versions. The workbench refuses to start, naming the lane and the key (for example
`services.game-service.image-digest` or `contracts.session-api`), when a required key is missing or malformed, and
names both lanes and the first differing key when two lanes serving the same effective digests declare anything else
differently: one bundle has one provenance. The designer application offers exactly these bundles for selection (see
"Guided setup"); it never shows a lane's identifier, endpoints or credentials. A case executes only on a free lane whose
content digest, service provenance and contract versions equal the variant's `effectiveContentDigest` and the services
and contracts its experiment pins. The lane's own rules digest takes no part in the match, because every case opens its
context with the variant's frozen effective rules and their digest; the lane's rules are the baseline variants are
built from. Each service must report that digest in its checkpoint, otherwise the attempt fails with
`CONFIGURATION_DRIFT` and never counts as a result, and the case's evidence records the digest each service reported.
Each attempt records the lane that played it. Cases are taken in matrix order among those some free lane can serve: a
case no free lane fits stays pending without holding back the cases behind it. A recovering attempt goes back to its
previous lane when that lane is free and still fits the case.
`startRun` checks the configured lanes, busy or free, so every instance serving the API needs the same lane
configuration as the worker. Logical time starts at
`workbench.execution.logical-epoch` for every attempt and is advanced only to the earliest deadline reported by the
services' checkpoints, on both services, followed by their drain barrier. A slow projection never moves time.

A game is settled when both services report their work drained and every consumer group has committed the end
offsets the event observer read from each source partition: the groups of game-service and timeline-service report
theirs in their checkpoints, and the runner reads those of read-service from the broker. The seats' states are read
only after that, so accepted state read before it is never treated as current. The runner polls every
`workbench.execution.barrier.poll-interval` (default `50ms`) for at most `workbench.execution.barrier.timeout`
(default `10s`); a wait that exceeds the timeout reports the game as still waiting, however short the poll interval is.
The simulator has no broker, so its observer reports the slot router's idle check in place of offsets.

A lane's services play one game per execution context. Whatever the outcome of an attempt or reproduction (succeeded,
failed, cancelled, timed out or interrupted), the runner closes the case's context on the game and timeline services
with `closeSimulationExecution` before the lane is offered again; the lane is never restarted between cases. A `204`
answer, or a `409 EXECUTION_NOT_CONFIGURED` answer (nothing was open), means the service holds no context of the case.
Any other answer, a connection failure or no answer within `reset-timeout` is a failed close. The close of each case is
timed as `workbench.lane.context.close`. After a failed close the lane is recovered through its deployment: a lane with a
`reset-url` (an absolute http or https URL; the workbench refuses to start otherwise) has the hook called, and a lane
without one has the close retried:

```http
POST {reset-url}
Authorization: Bearer <the lane's operator token>
Content-Type: application/json
Idempotency-Key: <a UUID, fresh for each release and repeated by its retries>

{"laneId": "lane-1", "caseKey": "<case key of the case the lane hosted>"}
```

Any `2xx` answer means the lane is clean. Until then the lane is offered to no case or reproduction. A non-`2xx`
answer, a call exceeding `reset-timeout`, or a connection failure is logged with the lane id and the status or error
and retried with a backoff growing from one second to one minute, for as long as the workbench runs; no case fails
because its lane is being recovered. A lane that was acquired but never sent a context needs no close.

An attempt interrupted by a lost lease or a stop closes its context like any other outcome. After a crash the context is
still open: on start every lane is unavailable until the runner has read each service's checkpoint and closed the
context it reports open, whichever case opened it. A recovering attempt then starts a new game; it never attaches to
the abandoned one.

An interrupted run is resumed with `resumeRun`; there is nothing to repair by hand. Only one runner instance should
execute against a database.

**Limitations.** A lobby whose creation response was lost cannot be found again, so that attempt fails and is
retried on a clean lane. Lane provisioning, cleanup, and image attestation belong to the deployment that owns the
lanes; the runner detects a case or manifest mismatch but does not provision anything.

### Simulator slots

A real lane is about thirteen containers. To play many more cases on an ordinary PC, the workbench can host the
three services in its own JVM instead. Each service runs unchanged under its `in-memory` Spring profile, which swaps
only its infrastructure adapters (database, broker, outbox) for in-memory ones. A **slot** is one such set of three
services. A slot plays one case at a time, and slots play in parallel. The runner, bot policies, evidence, reports and
reproduction are the ones real lanes use; an attempt records its slot as its lane (`simulator-1`, `simulator-2`, ...).

```yaml
workbench:
  simulator:
    slots: 2                      # 0 (the default) disables the simulator
    game-service-jar: /builds/game-service.jar
    timeline-service-jar: /builds/timeline-service.jar
    read-service-jar: /builds/read-service.jar
    rules-file: /builds/rules.yml   # the game.rules.* properties every service plays under (.yml, .yaml, .properties)
    work-directory: /var/tmp/workbench-simulator   # optional; where the jars are extracted
    bots: 6                       # bot identities per slot; at least the largest player count of a case
    bundle:                       # declared like a lane's; every slot serves this one bundle
      rules-digest: 3f1c…
      rules-document: /builds/effective-rules.json   # the EffectiveRules document of rules-file
      content-digest: 9b07…
      rules-artifact-digest: 5d2a…
      content-artifact-digest: 0c8e…
      source-revisions: { game-service: 1a2b3c4, timeline-service: 1a2b3c4, read-service: 9f8e7d6 }
      contracts: { session-api: 3.0.0, action-api: 7.1.0 }
```

The jars are the executable jars the services' builds produce, built from the declared source revisions. A service's
image digest in the bundle's provenance is the digest of its jar. The workbench refuses to start, naming the slot and
the path, when a jar or the rules file is missing or unreadable, and names the key when a declaration is missing or
malformed.

Bots call the services' REST operations on loopback ports, exactly as on a real lane, with tokens an in-process issuer
mints; the slot's services trust only that issuer, and its key never leaves the process. The services publish through
the slot's event sink, which delivers every record to the services that consume its topic: `game.events` to
`timeline-service` and `read-service`, `timeline.events` to `game-service` and `read-service`. Per topic and consuming
service, records arrive in publication order and one at a time, as a partition would deliver them for one game; the
settlement barrier waits until every routed record has been delivered. The observer retains each routed record as
delivered, the same raw evidence a real lane's observer retains.

A slot stops its services when its case ends and starts fresh ones for the next case: the services keep every game in
memory, so stopping them is what releases it. State lives only in memory, so a workbench restart cannot resume a game on
a slot.

The slots' behavior on the real service jars is covered by integration tests that run only when
`-Dsimulator.builds=<directory>` names a directory holding `game-service.jar`, `timeline-service.jar` and
`read-service.jar`: `SlotRuntimeRealJarsIT` starts the three services, `SimulatorLaneIT` plays a seeded case twice and
compares the semantic digests, and `SimulatorParallelSlotsIT` plays cases on two slots at once. `SimulatorCapacityIT`
measures one slot count; its results are in [docs/capacity/simulator-v1.md](docs/capacity/simulator-v1.md).

## Timing metrics

The workbench publishes Micrometer timers on its Prometheus endpoint (`/actuator/prometheus`) that split a game's wall
time. None carries a case key, seed, game id, player id or credential as a tag.

| Timer | Measures | Tags |
|---|---|---|
| `workbench.case.attempt` | Wall time of one attempt, from its start to its final state | `lane`, `players`, `state` (`SUCCEEDED`, `FAILED`, `CANCELLED`, `INTERRUPTED`), `failure` (failure code, or `none`) |
| `workbench.policy.decision` | Time a policy takes to produce one decision | `policy` (policy id; reproductions use `transcript`) |
| `workbench.lane.wait` | Time spent waiting for the services after a command | `kind` |
| `workbench.lane.context.close` | Duration of closing one case's execution context on both services | `lane`, `outcome` (`CLEAN`, `FAILED`, `TIMEOUT`) |
| `workbench.lane.reset` | Duration of one reset-hook call, which follows a failed close only | `lane`, `outcome` (`CLEAN`, `FAILED`, `TIMEOUT`) |

`workbench.lane.wait` has three kinds. `ACKNOWLEDGEMENT` is one sample per command sent, from the request until the
service answers (terminal readiness sends nothing and adds none). `CAUSAL_BARRIER` and `PROJECTION_BARRIER` are one
sample each per settle wait: the time until both services report their work drained, and the time until every consumer
group has applied the records the event observer has read and every seat's state has been read after that. Each poll's
pause is attributed to the barrier that was still unmet. An attempt whose lane lost its lease or was fenced out is recorded as `INTERRUPTED`.
Slots of the simulator stop their services instead of closing a context or calling a hook, and record neither
`workbench.lane.context.close` nor `workbench.lane.reset`. A case whose context closes cleanly adds no
`workbench.lane.reset` sample.

## Measuring capacity

`scripts/measure-capacity.sh` measures what one light simulation lane plays when every case opens and closes its own
execution context, and writes the report [docs/capacity/per-game-context.md](docs/capacity/per-game-context.md). The
procedure is re-runnable on any machine at least as large as the reference spec:

1. **Reference spec.** It is a setting: `CAPACITY_REFERENCE_THREADS` (default `8`, which is 4 cores / 8 threads) and
   `CAPACITY_REFERENCE_MEMORY_MB` (default `16384`, 16 GB). The script caps the lane's containers to the first
   `CAPACITY_REFERENCE_THREADS` logical processors (`docker update --cpuset-cpus`) and refuses to run when their memory
   limits add up to more than the memory cap or when Docker has fewer logical processors than the spec.
2. **Light lane.** It provisions the lane with the baseline bundle through the infrastructure repository's workbench
   E2E harness with `WORKBENCH_E2E_LANES=1`, which builds the services and this workbench from the checkouts that sit
   next to the infrastructure checkout.
3. **Experiment.** It freezes an experiment from the `baseline-policy-comparison` template with its defaults and the
   bundle the workbench lists.
4. **One lane.** It runs the experiment on that lane, `CAPACITY_REPETITIONS` times (default and minimum 2), and never
   starts a second lane: it stops when anything but the one lane project exists, and checks again after provisioning.
5. **Samples.** While the runs execute it samples every container of the lane with `docker stats` every two seconds,
   and it records each JVM's declared heap options and the maximum heap the JVM reports.

```bash
CAPACITY_REFERENCE_THREADS=8 CAPACITY_REFERENCE_MEMORY_MB=16384 \
  bash scripts/measure-capacity.sh ../infrastructure
```

It needs `docker`, `curl`, `jq` and `node`, and `MAVEN_SETTINGS` (the Maven settings the image builds use; it defaults
to a settings file with a `github` server entry made of `GITHUB_ACTOR` and `GITHUB_TOKEN`). It removes what it provisioned when it ends, unless `CAPACITY_KEEP=1`; raw
samples stay in `target/capacity`. The report states the reference spec and the caps applied, the host machine, the
service image digests and the effective rules and content digests, the games measured per player count and policy, the
games per hour on the one lane, the wall-time quantiles 0.1, 0.5 and 0.9 per player count and policy, the share of wall
time spent in play, policy decisions, service waits and context closes, the failed attempts by failure code, the
lane's footprint (peak memory and CPU of each container and each JVM's heap settings) and the variation between the
repetitions. Its wall-time breakdown shows a count of lane resets, which is zero unless a context close failed. It also lists,
per repetition and per kind of service wait (`ACKNOWLEDGEMENT`, `CAUSAL_BARRIER`, `PROJECTION_BARRIER`), the summed
seconds, the sample count and the mean seconds per sample, and the settle waits per game: the `CAUSAL_BARRIER` samples
over the games played.

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
observe and decide on. It waits, queued, while that lane is busy, shares the worker pool, and is taken after pending
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
is computed by analysis version `2` with analysis seed `1` (a comparison keeps the version it was created under); both
are on every report, comparison, and CSV row, and the same version, seed, and case results always produce
byte-identical documents. Analysis version `2` is version `1` plus the `failure_share` metric: every version `1` metric
keeps its value and interval.

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

A balance criterion version is a read-only document served from configuration: its lines (a metric, a dimension, the
bot policies and player counts it covers, a band, a role as `GATE` or `SCORED`, a weight), its aggregation, and its
evidence rule. `GET /api/v1/balance-criteria` lists the served versions and `GET /api/v1/balance-criteria/{version}`
reads one; its `digest` is the SHA-256 of its canonical serialisation (object keys sorted, no whitespace, numbers by value
without trailing zeros), so any change of any value changes it. A version whose evidence rule the evaluator cannot apply
(another confidence level, other invalidating causes, another aggregation) is refused at startup. A new version is added by adding `balance-criteria/v<N>.json` to the application's
configuration, never through the API; version 1 equals `docs/balance-criteria/v1.md` line by line, which a test checks.
A criterion names a policy as its id with the major version (`faction-greedy-v1` is policy `faction-greedy` at
`1.0.0`).

`GET /api/v1/runs/{runId}/evaluation?variantLabel=&criterionVersion=&mode=` (`simulation:read`) evaluates one variant of
a run against one criterion version. Nothing is stored: it is derived from the run's current cases on every read, with
the analysis seed, and the same run, variant, version, and mode always return byte-identical JSON. Lines are ordered by
line id, policy, player count, faction set, and faction.

- Each criterion line expands into one evaluated line per cohort and dimension value it covers. A faction ratio is the
  faction's win rate over `R`, the winner seat-games over the seat-games of the same cohort; the sum of failure codes is
  the sum of those `failure_share` codes. Both are recomputed in every resample of their cohort, so a line's interval
  is the interval of the derived value and never a combination of separate intervals. A cohort without a winner has no
  `R`, and its ratio lines are `NO_DATA`.
- Deviation is 0 inside the band and otherwise the distance to the nearer bound over the band width (or, for a
  one-sided limit, over its scale). A line is `MEETS` when its whole interval lies inside the band, `FAILS` when it lies
  wholly outside, and `INCONCLUSIVE` otherwise, and always `INCONCLUSIVE` without an interval. The score is the sum of
  `weight × deviation²` over the scored lines, from point values; it ranks candidates and never rejects or confirms.
- Validity comes first. The evaluation is `INVALID`, with every applicable reason and no score, when a case of the
  variant did not finish (`UNFINISHED_CASES`), any case failed with `CONTRACT_MISMATCH` or `CONFIGURATION_DRIFT`, the
  `EXECUTION_FAILED` share of a policy and player count is above the criterion's limit (`EXECUTION_FAILED_SHARE`), or
  a game ended with every player abandoned (`ALL_PLAYERS_ABANDONED`).
- A valid evaluation is `REJECTED` when a gate fails. Only in `CONFIRMATION` mode is it `CONFIRMED`, and only with a
  satisfied confirmation sample (at least the criterion's minimum seeds, and every faction set at 3, 4, and 5 players
  under every cyclic rotation for every policy the criterion names), every pooled gate meeting its band, and no scored
  line failing. Otherwise it is `UNCONFIRMED`; `SCREENING` mode never confirms.

Bot results say nothing about balance among human players, and an evaluation must not be presented as if they did.

### Exports

CSV exports have a header row and repeat the full attribution on every row: the format version, the run or
comparison, the analysis version and seed, completeness, the cohort, its counts, the metric with its statistic and
dimensions, and the values. Empty fields are null values. The report export's `failureCode` column, after `quantile`,
is empty for every metric not split by failure code, including the all-codes `failure_share`, and holds the failure
code of a per-code `failure_share` row. Exports carry no credentials or machine-local paths.

### Limitations

Results characterize the run's bot policies, not human balance, and are stratified by policy for that reason.
Card, special, and faction associations are observational: they describe how those policies played, not the
strength of a card or faction. Cases saved before action-round observations kept the whole hand hold the narrower
observation: their action rounds count as unobserved here, and reproducing them reports an `OBSERVATION`
divergence.

## Bot policies

Two baseline policies play every normal decision window. Each decision uses one frozen
`EntitledObservation` per seat: the seat's own faction and hand (in action rounds every card with its
playability, and the playable ones as candidates), the visible events (printed weights, plus an exact
weight only when the seat earned it, for example through Scan), the other participants' identifiers, and
the open window. Observer evidence, opposing hands or credentials, and execution-control state have no
field on the type, so they cannot influence a choice.

| Bundle | Id / version | Behavior |
|---|---|---|
| `random-v1` | `random` / `1.0.0` | Uniform seeded draw over the canonically ordered candidates, pass or decline included |
| `faction-greedy-v1` | `faction-greedy` / `1.0.0` | Scores each candidate as `affinity * 1000 + target lean`, then draws among the top scores with seeded entropy |

A manifest references a bundle by `id`, `version`, and `artifactDigest`. The digest is the SHA-256 of the
bundle identity and its canonical definition (for `faction-greedy-v1`, the full preference table), so a
changed preference needs a new bundle version. The baselines accept no parameters. An unknown id/version
or any parameter yields `INVALID_EXPERIMENT`; a wrong digest yields `MANIFEST_MISMATCH`.

Windows covered: seven-to-five hand selection, declaration or decline, action rounds (every card and
special target shape, or pass), paradox resolution (card or pass; an empty offer passes), and terminal
readiness. Candidates are enumerated in one canonical order from published submission shapes and entitled
state only; the services stay authoritative for legality, resolution, and scoring.

**Entropy.** Each stream is derived from the policy seed, seat, window key, and rejection count under its own
domain-separation label. Service entropy is never an input, so equal observation, version, and seed always give
the same choice.

**`faction-greedy-v1` preferences.** Each faction has an affinity per card type and special action, and a lean
toward the highest or lowest known outcome weight (suppress-style actions by Erasers and raise-style actions by
the others aim at the leading outcome). Activists prefer declaring over declining; reactive paradox cards rank
by faction (for example Stabilize for Prophets and Weavers, Detonate for Erasers). Players are targeted by seeded
tie-break only, since opponents' factions and hands are hidden.

**Window procedure.** The runner implements `ParticipantGateway` over the generated participant clients and drives
`DecisionWindowService`: every seat's observation is frozen and every choice computed before any submission, then
submissions go out in seat order. A rejected candidate is recorded and excluded. The budget counts
rejected candidates: while the seat's rejection count is still below the manifest's
`maxRejectedCandidatesPerWindow`, the policy reselects from a refreshed observation; once the count
reaches the budget, the seat instead submits an available pass or decline fallback, or returns
`POLICY_EXHAUSTED` (distinct from pass and decline) when none remains unexcluded. So a budget of `1`
means the first rejection goes straight to the fallback with no policy reselection, and a budget of
`2` means one policy reselection before the fallback; it never invents a legal choice. A missing acknowledgement is reconciled against accepted
state before any retry, so an accepted action is never spent twice; a seat whose accepted state never becomes current is reported as `ReconciliationPending`
(the runner must reconcile it before submitting again), and a seat shown not to have been spent after one
resubmission is reported as `NotAccepted`. Either way the results of the other seats are kept.

**Limitations.** Neither policy models authoritative resolution, scoring, or opponents, and terminal readiness has
no dedicated participant operation in the pinned contracts, so the gateway maps it to the runner's continuation
signal. Bot results characterize these baselines only and do not establish human balance.

## Application integration proof

The `proof` test package (`src/test/java/io/github/temporalrift/workbench/proof`) proves the capabilities work
together through the published HTTP boundary only: every step (freeze, preview, run, cases, report, comparison,
replay, reproduction, resume) is a request with a signed bearer token, against real PostgreSQL and Kafka
Testcontainers. Only the lane behind `LaneProvider` is scripted in-process; real game, timeline and read services
and the operator-control endpoints belong to their own repositories, and the deployed run is orchestrated by
`infrastructure`. A green run here says the workbench accounts for, reports and reproduces what its lane returns
truthfully; it says nothing about game balance.

| Suite | Proves |
|---|---|
| `CoverageMatrixIT` | The seed-42 matrix (all faction sets of 3, 4 and 5 players, `CYCLIC` rotations, both baseline policies, two rules variants) is 220 distinct coordinates, 55 per variant and policy; each is listed with an authoritative ending and final scores or an explicit failure code and reason; one seed claims no interval |
| `MultiSeedReportingIT` | Ten eligible Erasers samples with four wins, one shared, report and export `wins = 4`, 40% and 10%; one block states `INSUFFICIENT_SAMPLE` with no interval; a failed counterpart is listed and left out of the paired estimate; JSON and CSV repeat byte for byte |
| `ReproductionProofIT` | A saved case reproduces to `MATCH` and adds no sample; a tampered or missing pinned input returns `409 MANIFEST_MISMATCH` and creates no reproduction |
| `RecoveryProofIT` | A 1000-case run interrupted after 400 successes keeps those results unchanged on resume and never exceeds 1000 successes |
| `PerspectiveProofIT` | A `PLAYER` replay hides opposing hands and unearned facts; `OBSERVER` needs `simulation:observe`; forged observer-only evidence does not change a bot's decisions |
| `AccessBoundaryIT` | Tokens with only participant or `simulation:control` authority get `403` on every designer, report, replay and reproduction operation, and learn nothing |
| `HttpCaseLaneTest` | A lane answering `404 SIMULATION_CONTROL_UNAVAILABLE` or `403 SIMULATION_CONTROL_FORBIDDEN` fails the attempt with that code |
| `GameplaySeparationTest` | Gameplay services are reached only through generated clients in the lane adapter, no gameplay-service code is shared, and policies depend on no evidence or analysis types |

Run them with the rest of the integration suite (`mvn verify`), or alone:

```bash
mvn test-compile failsafe:integration-test -Dit.test='CoverageMatrixIT,MultiSeedReportingIT,ReproductionProofIT,RecoveryProofIT,PerspectiveProofIT,AccessBoundaryIT'
```

### Evidence files

`MultiSeedReportingIT` and `ReproductionProofIT` write `target/simulation-evidence/multi-seed-sample.json` and
`reproduction.json`. Both are assembled from API responses only (the experiment, run, report and case reads), so a
deployed run can produce the same documents from the same endpoints. They hold no timestamp and no credential, and
the same inputs give the same bytes.

| Field | Meaning |
|---|---|
| `formatVersion` | Layout version, currently `1` |
| `kind` | `MULTI_SEED_SAMPLE` or `EXACT_REPRODUCTION` |
| `experimentId`, `manifestDigest` | The frozen experiment and its digest |
| `timingMode`, `services`, `contracts`, `policies`, `variants` | The manifest's logical timing, pinned service images and revisions, contract versions, policy ids, versions and artifact digests, and each variant's effective rules and content digests |
| `runId`, `analysisVersion`, `analysisSeed`, `complete`, `caseCounts`, `cohorts`, `cases` | (`MULTI_SEED_SAMPLE`) the report with independent-block counts and intervals, and each case's key, variant, seed, state and semantic digest |
| `caseId`, `caseKey`, `savedSemanticDigest`, `reproductionId`, `state`, `firstDivergence` | (`EXACT_REPRODUCTION`) the saved case and how its re-execution ended: `MATCH` means the semantic digests are equal |
