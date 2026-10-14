# Spike: domain isolation for an in-memory game simulator

Answers [simulation-workbench#48](https://github.com/temporal-rift/simulation-workbench/issues/48), decision A-03 of
[infrastructure#124](https://github.com/temporal-rift/infrastructure/issues/124).

**Verdict: `GO WITH CHANGES`, for an in-process lane.** The simulator should host the real game-service,
timeline-service and read-service code in one JVM, behind the workbench's existing lane ports. Three blockers must be
fixed first (B1 to B3). Simulators that wire the rules code by hand, or that replace the services' persistence and
messaging with new in-memory adapters, are rejected for now (see "Options").

Revisions inspected: game-service `1c42ad4`, timeline-service `7ae1a1c`, read-service `58ad175`,
simulation-workbench `09b95f4`. No figure in this report is a measurement; throughput claims are marked as
hypotheses.

## 1. Rules code

| Service | Domain files | Framework imports in `domain/` | Driven ports | Application layer |
|---|---|---|---|---|
| game-service | 268 | 0 | 49 | 116 files; Spring annotations, Modulith listeners, transactions, schedulers |
| timeline-service | 126 | 0 | 25 | 29 files; Spring annotations, transactions, one scheduler |
| read-service | — | 0 | 21 | projection queries and event listeners |

- The domain packages are plain Java and can run without Spring, HTTP, Kafka or PostgreSQL.
- The application layer cannot run on its own. Game sequencing lives there and depends on Spring infrastructure:
  - 24 classes use `@ApplicationModuleListener`, which delivers module events asynchronously after the publishing
    transaction commits;
  - transaction synchronisation (`TransactionSynchronization`, `TransactionTemplate`) orders work around commits;
  - sweeps (`@Scheduled`) read the database to fire expiries.
- The persistence adapters carry semantics the rules depend on, in PostgreSQL-specific SQL. game-service has 132
  persistence adapter files: 32 `ON CONFLICT` (idempotency claims), 11 `jsonb` and 23 `PESSIMISTIC_WRITE` locks.
  timeline-service has 7, 4 and 2 of those; read-service has 3, 17 and 1.

## 2. Cross-service sequence

- **game-service out:** module events go to the event publication registry, then
  `GameEventsOutboxRelay` publishes them to `game.events`.
- **timeline-service in:** `TimelineGameEventsKafkaConsumer` dispatches each record on a game partition to focused
  transactional handlers (`EraStartedGameEventHandler`, `EventsDrawnGameEventHandler`,
  `RoundResolutionGameEventHandler`). `GameEventIngestion` is their shared validation and idempotency-claim
  prologue. `FactionAssignedKafkaConsumer` runs in its own consumer group.
- **timeline-service out:** `TimelineEventPublisherAdapter` publishes to `timeline.events`.
- **game-service in:** `BandsPublishedKafkaConsumer`, `EraResolutionCompletedKafkaConsumer`,
  `ResolutionFailedKafkaConsumer`, `PlayerReconnectKafkaConsumer`, `TimelineScoringKafkaConsumer` and
  `ParadoxResolutionPhaseKafkaConsumer`.
- **Timers:** under logical time, `TimerTaskScheduler` leaves one-shot timers unarmed. The database sweeps
  (`ActionRoundTimerSweep`, `HandSelectionTimeoutSweep`, `DeclarationPhaseTimeoutSweep`,
  `PlayerReconnectTimerSweep`, `ParadoxResolutionTimerSweep` and others) read the logical clock and own every
  expiry. `AdvanceSimulationClockCommandHandler` advances the clock and announces `LogicalClockAdvanced`; due-time
  handlers then run asynchronously, and the workbench sees completion through the checkpoint barrier.

The order of these steps is a rules concern. `TimelineGameEventsKafkaConsumer` commits each record's transaction
before dispatching the next, so actions cannot overtake drafting, resolution cannot overtake replay, and clean-up
cannot overtake the effects it removes. Any simulator that does not run
this code as it is must reproduce that ordering exactly.

## 3. Entropy and time

- Randomness is derived per purpose. `EntropyDerivation` (domain) sits behind the `ExecutionEntropy` port,
  implemented by `SeededExecutionEntropy`, and each decision is recorded through `EntropyDecisionRepository`.
  timeline-service has the same structure, with `EntropyPurpose` and `EntropyCoordinate`.
- Time is `LogicalClock`, behind `LogicalClockControl`, advanced only through the simulation control operation.
- If the simulator runs this code unchanged, a game with the same seeds makes the same draws as a real lane.
- **Constraint:** `ExecutionRepository.find()` and `findWithLock()` take no game or context key. Each service
  instance holds exactly one execution and one logical clock, so one instance plays one game at a time.

## 4. Bot observations

- Bots observe only read-service. `HttpParticipantGateway` reads each seat's `PlayerGameStateResponse` from the
  projection API (`getGameState`). `ObservationMapper` builds the entitled observation from that response alone.
- Hand playability, revealed intel and pending windows therefore come from read-service's projection of both
  services' events.
- **Consequence:** a simulator must host read-service too. Rebuilding the view inside the simulator would
  re-implement projection and privacy rules.

## 5. One classpath

- **Root packages differ:** `io.github.temporalrift.game`, `.timeline` and `.read`.
- **Shared BOM:** all three use `temporal-rift-bom` 2.0.1, so their dependency versions align.
- **No generated event types:** event payloads are not generated classes; each service maps the bundled AsyncAPI
  payloads itself. Generated REST models sit under each service's own package. No class clashes are expected.
- **No reusable artifact:** none of the three services publishes a jar; lanes build images from source. The
  simulator needs each service's classes at the lane's exact revision (blocker B2).
- **Not checked:** static mutable state in the services, which would leak between contexts in one JVM. It must be
  checked in S-12.

## 6. Options

| Option | What runs | Rules reuse | Fidelity risk | Change size |
|---|---|---|---|---|
| A. Hand-wired | Domain and application classes built without Spring, with new in-memory implementations of all 95 ports | Rules yes; orchestration re-built by hand | High: the async after-commit event semantics, the ordering of the 15 classes with Kafka listeners, and idempotency and locking behind PostgreSQL SQL must all be re-created | Large, mostly in the simulator, and it drifts with every service change |
| B. In-process lane | The three Spring applications as separate contexts in one JVM, with one embedded PostgreSQL process (three databases) and one in-JVM Kafka broker | Everything, including adapters | Low: same code paths; only process boundaries change | Medium (B1 to B3) |
| C. In-memory adapters | Each service ships an alternative set of persistence and messaging adapters for simulation | Rules and orchestration; adapters re-implemented | Medium: the in-memory adapters must reproduce idempotency, locking and ordering | Large, spread over three services |

**Choice: B.** It is the only option where the simulator runs no code the real lanes do not, which keeps
`MATCH` against a real lane a reasonable expectation. Most of the per-game time saving comes from removing the
per-game restart, which needs the per-game execution context (B1) whichever option is built. **[Hypothesis]**
B's further gain over a light real lane is memory density: one JVM instead of four, and no Docker. That allows more
games in parallel on the same PC. This must be measured before C is considered.

C is not built now. It becomes a candidate only if a measured B cannot meet the search budget, and then only for the
adapters the measurement shows to be the bottleneck.

## 7. Blockers

| ID | Repo | Boundary change | Size |
|---|---|---|---|
| B1 | apis (`simulation-control-api`), game-service, timeline-service | The execution context, logical clock and entropy are scoped per game. A control operation closes the current context, clears its state and opens a new one carrying that game's rules, without restarting the service. This is story S-08 of infrastructure#124, needed by real lanes and the simulator alike | Large |
| B2 | game-service, timeline-service, read-service | Each build also produces a plain library jar (not the repackaged boot jar) of the same revision, so the simulator can load the classes the lane images are built from | Small |
| B3 | game-service, timeline-service, read-service | Each application starts as an embedded context with externally supplied datasource, broker and issuer settings and an ephemeral server port. No static mutable state is shared between contexts | Small to medium |
| B4 | simulation-workbench | An in-process `LaneProvider` and `CaseLane` that start the embedded database, broker and three contexts once, mint deterministic bot, operator and observer tokens with an in-process signing key, and implement `GameSession` and `GameEventObserver` against them | Medium |
| B5 | simulation-workbench | A case played in the simulator can be reproduced on a real lane, which today is refused because a reproduction runs only on the lane that played the case. Bot identities must be the same deterministic subjects on both | Small to medium |

B1 to B3 must land before B4 can play a game. B5 is needed for the fidelity check.

**Parallel games.** One service instance plays one game (section 3), so parallel games need several context sets
in one JVM, each with its own databases and topics. Memory per context set is not known; S-12 must measure it.

## 8. Fidelity check

Proposed protocol; the pass condition needs design agreement (A-03):

1. Play a set of cases in the simulator: every player count, both baseline policies, at least two seeds.
2. Reproduce each one on a light real lane through the existing reproduction (needs B5). Because option B runs the
   same code, the expected result is `MATCH` for every case. Any divergence is a defect to fix, not noise to
   tolerate.
3. Run the same experiment on both and compare the reports. Identical semantic digests imply identical metric
   values; any difference identifies the cases to inspect.
4. Repeat the check whenever a service revision or a lane bundle changes. The simulator is valid only for the
   revisions it was checked against.

## 9. If the verdict is overturned

If B1 is rejected or proves infeasible, no option is viable without re-implementing orchestration. The search then
runs on one light real lane, with games played one after another, and the P-01 budget of infrastructure#124 bounds
its size.
