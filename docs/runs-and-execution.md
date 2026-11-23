# Runs and durable execution

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
| `POST /api/v1/runs/{runId}/cases/{caseId}/reproductions` | `202`; executes the pinned case again in a clean lane (see [Reproduction](evidence-and-reports.md#reproduction)) |


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
a case starts. Each case plays in an execution context of its own, opened on the lane's game, timeline and read services
and closed when the case ends, so a lane plays any number of cases in sequence without restarting; a lane that declares a
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
failed, cancelled, timed out or interrupted), the runner closes the case's context on the game, timeline and read
services with `closeSimulationExecution` before the lane is offered again; the lane is never restarted between cases. A `204`
answer, or a `409 EXECUTION_NOT_CONFIGURED` answer (nothing was open), means the service holds no context of the case.
Any other answer, a connection failure or no answer within `reset-timeout` is a failed close. The close of each case is
timed as `workbench.lane.context.close`. After a failed close the lane is recovered through its deployment: a lane with a
`reset-url` (an absolute http or https URL; the workbench refuses to start otherwise) has the hook called, and a lane
without one has the close retried. A service that refuses a case's context with `409 EXECUTION_STATE_NOT_CLEAN` still
holds an earlier case, which no close can remove: the lane is reset before it hosts anything, as after a failed close,
and the case goes back to pending without spending an attempt or recording a failure. A lane without a `reset-url`
cannot be cleaned that way and stays out of service until the workbench restarts:

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
    rules-file: /builds/rules.yml   # the services' game.* configuration: rules, timers, limits, catalog (.yml, .yaml, .properties)
    work-directory: /var/tmp/workbench-simulator   # optional; where the jars are extracted
    bots: 5                       # bot identities per slot; at least the largest player count of a case
    idle-timeout: 10m             # a slot that hosted no case for this long stops its services; 0 keeps them running
    bot-player-ids:               # optional: one player id per bot, in seat order; see [Simulator fidelity](fidelity-checks.md)
      - 6c0f5b1e-0000-4000-8000-000000000001
      - 6c0f5b1e-0000-4000-8000-000000000002
      - 6c0f5b1e-0000-4000-8000-000000000003
      - 6c0f5b1e-0000-4000-8000-000000000004
      - 6c0f5b1e-0000-4000-8000-000000000005
    bundle:                       # declared like a lane's; every slot serves this one bundle
      rules-digest: 3f1c…
      rules-document: /builds/effective-rules.json   # the EffectiveRules document of rules-file
      content-digest: 9b07…
      rules-artifact-digest: 5d2a…
      content-artifact-digest: 0c8e…
      source-revisions: { game-service: 1a2b3c4, timeline-service: 1a2b3c4, read-service: 9f8e7d6 }
      contracts: { session-api: 3.0.0, action-api: 7.1.0 }
```

The rules file stands in for the configuration server a lane's services read: it must carry every `game.*` value the
services need, not only `game.rules.*` but also their `game.timers.*` and limits, and the event catalog (`game.catalog`).
A service missing a value it requires, such as a timer interval, does not start, and the slot fails every case it is
given. A multi-document YAML file is read as one set of properties, in document order: when two documents set the same
property the first one wins, unlike Spring Boot config data, where the last one does. Conditions such as
`spring.config.activate.on-profile` are not applied, so every document counts; resolve conditional documents before
using the file. The jars are the executable jars the services' builds produce, built from the declared source
revisions. A service's
image digest in the bundle's provenance is the digest of its jar. The workbench refuses to start, naming the slot and
the path, when a jar or the rules file is missing or unreadable, and names the key when a declaration is missing or
malformed.

Bots call the services' REST operations on loopback ports, exactly as on a real lane, with tokens an in-process issuer
mints. As on a real lane, game-service runs with a per-player rate limit of 1,000,000 requests a minute whatever the rules
file says, because bots play without pause and the ordinary limit would answer them `429`; the slot's services trust only that issuer, and its key never leaves the process. The services publish through
the slot's event sink, which delivers every record to the services that consume its topic: `game.events` to
`timeline-service` and `read-service`, `timeline.events` to `game-service` and `read-service`. Per topic and consuming
service, records arrive in publication order and one at a time, as a partition would deliver them for one game; the
settlement barrier waits until every routed record has been delivered. The observer retains each routed record as
delivered, the same raw evidence a real lane's observer retains.

A slot starts its services for its first case and keeps them for the cases that follow, as a real lane does: each case's
execution context is closed on its three services before the next is opened, which leaves them as clean as newly
started ones. A slot restarts its services only to recover, after a failed close or a service that answered
`EXECUTION_STATE_NOT_CLEAN`, and stops them once it hosted no case for `idle-timeout`, so a workbench with no run in
progress gives their memory back. Stopped services leave nothing behind: their class loaders are collected, so a run's
memory depends on the number of slots, not on the cases it plays. State lives only in memory, so a workbench restart
cannot resume a game on a slot.

The slots' behavior on the real service jars is covered by integration tests that run only when
`-Dsimulator.builds=<directory>` names a directory holding `game-service.jar`, `timeline-service.jar` and
`read-service.jar`: `SlotRuntimeRealJarsIT` starts the three services, `SimulatorLaneIT` plays a seeded case twice and
compares the semantic digests, and `SimulatorParallelSlotsIT` plays cases on two slots at once. `SimulatorCapacityIT`
measures one slot count; its results are in [docs/capacity/simulator-v1.md](capacity/simulator-v1.md) and, for the full matrix through the run API, [docs/capacity/simulator-v2.md](capacity/simulator-v2.md).
