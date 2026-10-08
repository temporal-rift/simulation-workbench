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
| `execution` | Durable real-service batches: runs, cases, attempts, leases, command reconciliation, cancel and resume |
| `policy` | Versioned baseline bot policies |
| `analysis` | Balance comparisons with truthful statistics and exports (later package) |

`domain/` is plain Java, `application/` never depends on `infrastructure/`, and modules communicate
only via `ApplicationEvent`. All service boundaries are generated from the pinned contract modules;
no hand-written copy of engine or player-contract types may be introduced here.

## Authorization

OAuth2 resource server (JWT bearer). Reads require `simulation:read`; mutations require
`simulation:write`; observer replay additionally requires `simulation:observe`.

## Experiments

`POST /api/v1/experiments` with an `Idempotency-Key` UUID header freezes a complete
`ExperimentManifest` and returns `201 Experiment` (`experimentId`, `manifest`, `manifestDigest`
SHA-256 hex, `createdAt`). The digest is computed over a canonical form of the manifest, so only a
meaning change (for example score threshold 20 vs 22) changes it — never key order or whitespace.
Experiments are immutable after creation: a configuration change creates a new experiment.

Idempotency: repeating a key with a byte-identical body returns the original experiment; reusing a
key with a changed body returns `409 IDEMPOTENCY_CONFLICT`.

Error codes (RFC 9457 problem details): `400 INVALID_EXPERIMENT` (structural validation, including
duplicate factions, bad counts/seeds, credentials or machine-local paths), `409 MANIFEST_MISMATCH`
(malformed artifact references, image digests, source revisions, or contract pins),
`409 EXPERIMENT_IMMUTABLE` (any edit to a launched manifest), `409 IDEMPOTENCY_CONFLICT`,
`401 UNAUTHORIZED`, `403 INSUFFICIENT_SCOPE`, `404 RESOURCE_NOT_FOUND`.

Retention: the frozen manifest is persisted with its full configuration/content/policy artifacts,
seeds, faction sets, rotations, and execution bounds — never digests alone, and never credentials
or machine-local paths. Resolved external bytes (rules bundles, policy artifacts, service images)
land with the evidence store.

## Cohort-matrix preview

`MatrixPreviewService` deterministically enumerates every case coordinate (seed, faction set, seat
rotation, policy assignment, variant) of a frozen manifest: complete faction sets (10 combinations
at 3 players, 5 at 4, 1 at 5) under `CYCLIC` rotation place every faction in every seat, giving
30 + 20 + 5 = 55 base cases per seed, policy, and variant — 220 cases per seed with two homogeneous
policies and two variants. Ordering is stable and every coordinate carries a deterministic `caseKey`;
the durable runner persists these keys without recomputation. No REST preview endpoint exists in the
published boundary, so preview is a domain service covered by unit tests, not a controller.

## Runs and durable execution

`POST /api/v1/experiments/{experimentId}/runs` (body `{}`, `Idempotency-Key` header) creates a `Run` over a
frozen experiment and returns `202` in state `QUEUED` with its counts. One logical case exists per matrix
coordinate and is persisted up front, so the totals are known before anything executes. A manifest may expand to at most 100,000 cases, with `concurrency` up to 64, `caseWallTimeoutSeconds` up to 86,400 and `maxRejectedCandidatesPerWindow` up to 1,000; larger ones are `INVALID_EXPERIMENT`. The same key returns the
original run; the same key for another experiment returns `409 IDEMPOTENCY_CONFLICT`.

| Operation | Behavior |
|---|---|
| `GET /api/v1/runs/{runId}` | State, timestamps, failure, and case counts (`requested`, `pending`, `running`, `succeeded`, `failed`, `cancelled`) |
| `GET /api/v1/runs/{runId}/cases/{caseId}` | The logical case, every attempt, and the reconciled result |
| `POST /api/v1/runs/{runId}/cancel` | `202` on the first accepted cancel, `200` once the run is already terminal |
| `POST /api/v1/runs/{runId}/resume` | `202`, legal only from `INTERRUPTED`; any other state returns `409 INVALID_RUN_STATE` |

The report, replay, and reproduction operations of the same boundary answer `501 NOT_IMPLEMENTED` until they are
delivered.

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
| `CONFIGURATION_DRIFT` | A service reports another case or manifest than the one configured, or reveals another faction than planned | no |
| `POLICY_EXHAUSTED` | A seat had no candidate, pass, or decline left | no |

### Leases, recovery, and reconciliation

Pending cases are the jobs. A claim opens a new attempt that owns the case through a lease, renewed on every step
of the game; every outcome is written only by the lease owner of a still-running attempt. A worker whose attempt was
interrupted or recovered can therefore never overwrite the case, and a run's concurrency bound holds across
workers because claims on one run are serialized.

On start, the process interrupts every run a previous process left running; a graceful stop does the same for its
own attempts; a lease that expires without renewal interrupts its run. Completed results are never touched.
`resumeRun` re-queues the run, and only the unfinished cases are scheduled. A new attempt attaches to the game its
predecessor started when the lane still hosts that case, resolves whatever the predecessor left in doubt from the
service's accepted state, and plays on; otherwise it starts a new game on the lane, which must be clean, and the abandoned game's
decisions are forgotten.

Every decision a seat sends is recorded in the command ledger before it is sent, one slot per case, seat, and
window. A slot that was sent and never acknowledged is in doubt: it is reconciled against the seat's own accepted
state once the services have drained and the projection has stopped changing, and it is sent again only when
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
a case starts. Lanes and credentials are runtime configuration only; they are never stored with experiments, runs,
or evidence:

```yaml
workbench:
  execution:
    worker-enabled: true        # false for an API-only instance
    worker-threads: 2           # defaults to one per lane
    lease: 60s
    heartbeat: 10s
    max-attempts-per-case: 2
    lanes:
      - id: lane-1
        game-service-url: https://lane-1.game.internal
        timeline-service-url: https://lane-1.timeline.internal
        read-service-url: https://lane-1.read.internal
        operator-token: ${LANE_1_OPERATOR_TOKEN}
        bots:
          - { player-id: 8a0c…, token: ${LANE_1_BOT_0_TOKEN} }
```

A case executes only while a lane is free; with none configured, runs start but their cases stay pending. Logical time starts at
`workbench.execution.logical-epoch` for every attempt and is advanced only to the earliest deadline reported by the
services' checkpoints, on both services, followed by their drain barrier. A slow projection never moves time.

An interrupted run is resumed with `resumeRun`; there is nothing to repair by hand. Only one runner instance should
execute against a database.

**Limitations.** A lobby whose creation response was lost cannot be found again, so that attempt fails and is
retried on a clean lane. Lane provisioning, cleanup, and image attestation belong to the deployment that owns the
lanes; the runner detects a case or manifest mismatch but does not provision anything.

## Bot policies

Two baseline policies play every normal decision window. Each decision uses one frozen
`EntitledObservation` per seat: the seat's own faction, the visible events (printed weights, plus an exact
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
submissions go out in seat order. A rejected candidate is recorded, excluded, and the seat reselects from a
refreshed observation, up to the manifest's `maxRejectedCandidatesPerWindow`. After the budget, or when no
candidate remains, the policy chooses an available pass or decline, or returns `POLICY_EXHAUSTED` (distinct from
pass and decline); it never invents a legal choice. A missing acknowledgement is reconciled against accepted
state before any retry, so an accepted action is never spent twice; a seat whose accepted state never becomes current is reported as `ReconciliationPending`
(the runner must reconcile it before submitting again), and a seat shown not to have been spent after one
resubmission is reported as `NotAccepted`. Either way the results of the other seats are kept.

**Limitations.** Neither policy models authoritative resolution, scoring, or opponents, and terminal readiness has
no dedicated participant operation in the pinned contracts, so the gateway maps it to the runner's continuation
signal. Bot results characterize these baselines only and do not establish human balance.
