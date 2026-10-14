# Spike: domain isolation for an in-memory game simulator

Answers [simulation-workbench#48](https://github.com/temporal-rift/simulation-workbench/issues/48), decision A-03 of
[infrastructure#124](https://github.com/temporal-rift/infrastructure/issues/124).

**Verdict: `GO WITH CHANGES`, with in-memory infrastructure adapters.** The simulator runs the real
game-service, timeline-service and read-service code unchanged in one JVM: rules, application flow and Spring
wiring. Only the infrastructure adapters behind their ports are swapped for in-memory implementations, enabled only
in simulation. This is what the services' ports and adapters architecture is for. Nothing in the rules or the
application layer is redone. The work is the in-memory adapters themselves (B1 to B3) and the simulator lane in the
workbench (B4).

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
  simulator needs each service's classes at the lane's exact revision (part of blockers B1 to B3).
- **Not checked:** static mutable state in the services, which would leak between contexts in one JVM. It must be
  checked in S-12a to S-12c of infrastructure#124.

## 6. Options


| Option | What changes | Rules and flow | Fidelity risk |
|---|---|---|---|
| A. Hand-wired | Domain and application classes built without Spring, with the flow re-created by hand | Flow re-implemented | High: the async after-commit event semantics, the ordering of the 15 classes with Kafka listeners, and idempotency and locking must be re-created |
| B. In-process processes | The three services in one JVM, with an embedded PostgreSQL process and an in-JVM Kafka broker | Unchanged, including adapters | Low, but it removes only Docker: every game still pays real database and broker round trips |
| C. In-memory adapters | The three services in one JVM, Spring and application layer unchanged; only the infrastructure adapters behind the ports are swapped for in-memory implementations in simulation | Unchanged | Low to medium: the in-memory adapters must keep the production adapters' behaviour (duplicate-event claims, due-time queries, ordering); a port contract test suite run against both adapter sets controls this |

**Choice: C.** The ports and adapters architecture was built so infrastructure can be swapped without touching the
rules or the flow, and C uses exactly that. A re-creates the flow, so it is a second engine. B keeps the expensive
part, real database and broker work on every step, and gains little over a light real lane. C removes it, so
**[Hypothesis]** it is the only option that can be much faster per game; its games per hour must be measured.

## 7. Blockers

| ID | Repo | Change | Size |
|---|---|---|---|
| B1 | game-service | In-memory implementations, enabled only in simulation, of its 49 driven ports, of its inbound and outbound messaging, and of the event publication registry. The messaging boundary offers an outbound event sink and an inbound delivery entry that runs the same handling as its Kafka listeners. A port contract test suite runs against both the production and the in-memory adapters. The build also produces a plain library jar, and the application starts as an embedded context with no database, broker or fixed port | Large (mostly mechanical) |
| B2 | timeline-service | Same as B1 for its 25 driven ports, keeping the per-game ordering of `TimelineGameEventsKafkaConsumer` | Medium |
| B3 | read-service | Same as B1 for its 21 driven ports, so the bots' views come from its unchanged projection code | Medium |
| B4 | simulation-workbench | An in-process `LaneProvider` and `CaseLane` that host the three services in one JVM, route their events in order per game, mint deterministic bot, operator and observer tokens in-process, and implement `GameSession` and `GameEventObserver` | Medium |
| B5 | simulation-workbench | A case played in the simulator can be reproduced on a real lane, which today is refused because a reproduction runs only on the lane that played the case. Bot identities must be the same deterministic subjects on both | Small to medium |

B1 to B3 can proceed in parallel and must all land before B4 can play a game. B5 is needed for the fidelity check.

**Parallel games.** One service instance holds one execution and one logical clock (section 3). Parallel games need
either several instances of the three services in the JVM, or the per-game execution context of story S-08 of
infrastructure#124. With in-memory adapters, a service instance is cheap, but its memory is not measured yet.

## 8. Fidelity check

Proposed protocol; the pass condition needs design agreement (A-03):

1. Play a set of cases in the simulator: every player count, both baseline policies, at least two seeds.
2. Reproduce each one on a light real lane through the existing reproduction (needs B5). The rules and flow are the
   same code, so the expected result is `MATCH` for every case. Any divergence points at an in-memory adapter that
   behaves differently from its production counterpart, and is a defect to fix.
3. Run the same experiment on both and compare the reports. Identical semantic digests imply identical metric
   values; any difference identifies the cases to inspect.
4. Repeat the check whenever a service revision or a lane bundle changes. The simulator is valid only for the
   revisions it was checked against.
