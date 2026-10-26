# Spike: why games on the light lane wait so long, and how to make them faster

Answers [simulation-workbench#97](https://github.com/temporal-rift/simulation-workbench/issues/97), under
[infrastructure#124](https://github.com/temporal-rift/infrastructure/issues/124).

**Verdict: the waits are the runner's own settle loop, not the services.** After every change to the game, the runner
declares the game settled only once the projection has shown the same revisions on two consecutive reads, 500 ms
apart. That costs at least 1 s per decision point however fast the services are, and a game has dozens of them. The
fix is a causal barrier that compares offsets instead of waiting for quiet, and it leaves the semantic digest
unchanged. Shortening the poll interval alone is not safe (section 4).

Revisions read: simulation-workbench `1cc4009`, game-service `e696c73`, timeline-service `370acb4`, read-service
`2adbea6` (each `main` when read). Nothing here is a new measurement: the lane was not run. Statements marked
*inferred* follow from the code and the published capacity figures; the one split that would prove them is listed
under "What is not established".

## 1. What the waits are made of

`workbench.lane.wait` has three kinds (README, "Timing metrics"), and the capacity report sums them into one figure.
In the code that records them:

| Kind | One sample is | Made of |
|---|---|---|
| `ACKNOWLEDGEMENT` | One command's request until the service answers | Service latency only |
| `CAUSAL_BARRIER` | One settle wait, summed over its polls | The two services' checkpoint reads, plus each pause while a service was not drained |
| `PROJECTION_BARRIER` | One settle wait, summed over its polls | Every seat's projection read, the observer's drain, plus each pause once both services were drained |

A settle wait (`HttpGameSession.settle`) loops: read both checkpoints, read every seat's projection, return when both
services are drained and the projection is stable, otherwise pause `workbench.execution.barrier.poll-interval` and
repeat. The defaults are `poll-interval` 500 ms, `stable-polls` 2 and `max-polls` 20, and neither the infrastructure
repository's harness nor the capacity run overrides them.

The driver (`CaseDriver.playToEnd`) settles once per iteration: poll, then either play the open decision windows or
advance the clock, then poll again, until the game ends, followed by waits for the ending. So the number of settle
waits in a game is the number of its decision windows and clock advances.

## 2. The cause

Stability is counted across settle waits and is not reset by a command (`stableReads` and `lastRevisions` live on the
session). A command that changes the projection therefore always needs the same sequence:

1. first poll: the revisions differ from the last read, so the count is 0, and the runner pauses 500 ms;
2. second poll: equal, count 1, and it pauses 500 ms again;
3. third poll: equal, count 2, settled.

Two pauses, 1.0 s, is the floor of every settle wait that follows a change, whatever the services' latency. A game that
takes 28 to 53 s with 96% of it spent waiting holds roughly 27 to 50 such waits. That is the right size for a game
of three eras with a handful of windows and clock advances in each, but the report holds no count of settle waits
(*inferred*).

Evidence that the floor, and not the services, sets the time:

- **Two backends, one game time.** The in-process simulator hosts the same services over loopback with no Kafka,
  outbox or database, and its slots use the same `HttpGameSession` with the same barrier settings. It plays a
  three-player `random-v1` game in about 35 s on one slot (104 games an hour, `docs/capacity/simulator-v1.md`); the
  light lane takes a median of 44 s for the same shape. Services that answer in milliseconds do not shorten the
  game. The simulator figure includes starting the services, so it bounds the difference between the backends at
  about 9 s of 44.
- **Game length, not seat count, drives the time.** Going from three to five players adds 16% (`faction-greedy`: 33.5
  to 38.9 s) and 17% (`random`: 44.3 to 51.9 s) while the reads per poll grow by two thirds. The rest is spent per
  decision point, and `random-v1` games, which have more of them, take 11 s longer than `faction-greedy-v1` games at
  three players.
- **The pause is in the right place.** The pause is attributed to the barrier still unmet, so it is in the 96%; the
  only other sleeps are the start reconcile and a clock advance with no deadline, neither on the common path.

## 3. What each candidate cause contributes

| Candidate | Verdict | Why |
|---|---|---|
| Pauses of the stability rule | **Dominant** | At least 1.0 s per change, 27 to 50 times a game (section 2) |
| Service latency (acknowledgements, Kafka hops, projection) | Small | The simulator removes it and the game time barely moves |
| Checkpoint cost on the light lane | Unknown, bounded by the 17% above | Each checkpoint lists the consumer groups and reads their offsets from the broker; absent in the simulator |
| Policy decision | Negligible | Well under a millisecond |
| Context close | 0.5% | About 0.2 s a game |

## 4. Why not just shorten the poll interval

It would be fast and unsafe, for two reasons that both come from the stability rule being a statement about time.

- **Early settle.** Two equal reads 500 ms apart say "nothing moved for half a second". The chain from a command to the
  projection crosses game-service, Kafka, timeline-service, Kafka again and read-service. At 50 ms apart, a hop slower
  than that, such as a garbage collection or a consumer's poll wait, settles the game before the chain finished, and the
  bots then decide on a stale observation.
- **Early give-up.** `max-polls` counts polls, so 20 polls of 500 ms is a 10 s budget and 20 polls of 50 ms is 1 s. A
  settle wait that gives up returns `Waiting`, and the driver then calls `advanceClock`, which moves the logical clock
  to the services' earliest deadline while the game is unsettled.

Both change which commands are accepted and when timers fire, so both change the semantic digest.

The rule has a related gap that the cause above makes visible (*inferred* from the code, not reproduced). Because
`stableReads` is not reset by a command, a poll that runs before the projection has taken a command's effect sees
revisions equal to the last read and an already satisfied count. It settles if both services report drained and the
observer has reached the end offsets of what is published so far. The services' drained flag is service-local and
orders neither against the other (`ExecutionCheckpoint`'s own contract), so a timeline-service that has not yet
consumed the record reports drained. The window is the Kafka hop after the game-service relays, a few milliseconds
to tens of milliseconds, against a sub-millisecond check, which is why the pause rarely hides it. Tests that
play a seeded case twice and compare digests would not necessarily catch it.

## 5. The proposed change

Replace "wait until the projection has been quiet for a while" with "wait until every consumer has applied every record
that was published", which does not depend on time.

**Change 1, runner logic (workbench only).** Settled means all of:

1. both services report drained (unchanged);
2. for every topic partition of the game, the end offset the observer already reads in `drain()` is at most the
   committed offset of every consumer group of game-service and timeline-service, which their checkpoints already carry
   as `sourceWatermarks` (the runner ignores them today);
3. the same holds for read-service's groups, read from the broker by the workbench's own Kafka connection (the
   observer already holds one), because read-service has no checkpoint.

Poll at a short fixed interval (tens of milliseconds) and bound the wait by elapsed time instead of by poll count.
Delete the revision stability rule and `stable-polls`. A settle wait then lasts as long as the slowest hop in the
chain and not a multiple of 500 ms.

**Change 2, service change (only if change 1 shows read-service's committed offsets are not sound).** Committed
offset is taken after the listener returns, which for read-service is after its projection transaction commits, so
the offset should be sufficient. If measurement shows otherwise, read-service exposes the highest applied offset of
each source partition in the game-state response or a checkpoint. This is the clean design if a new surface is
needed; it is the same shape game-service and timeline-service already expose.

**Change 3, service change, optional.** Game-service and timeline-service build each checkpoint with a broker round
trip per consumer group (`KafkaSourceWatermarks`). Once the runner polls every few tens of milliseconds, that cost is
paid far more often than today. Cache the offsets inside the service or read them from the consumer's own position.

**Not proposed:** shortening `poll-interval`, `stable-polls` or `max-polls` on their own (section 4), and raising
the number of lanes, which multiplies the same waits.

## 6. Expected effect

The floor per settle wait goes from at least 1.0 s to the latency of the slowest hop, which is not measured but is
expected to be tens to a couple of hundred milliseconds on a lane. With the same 27 to 50 settle waits, a game takes
roughly 3 to 13 s in place of 28 to 53 s, which is a factor of 4 to 10 in games per hour (about 88 now). At that rate
the epic's budget of about 11,000 confirmation games takes 12 to 30 hours of one lane, down from about 125. These
are estimates from the model in section 2, to be replaced by the measurement in follow-up 1. The ceiling is set by
what remains once the pauses are gone: acknowledgements, play, the context close (0.5%) and the hop latency itself.

## 7. The semantic digest

The digest covers the case key, seed, seats, the accepted commands and the ending. It does not contain time. It stays
the same when two conditions hold: the bots decide on the settled state of the game, and the clock advances only at a
settled point. The causal barrier makes "settled" a function of offsets and therefore of the game, never of
timing, so it satisfies both more strongly than the stability rule does. The check is mechanical: play the same seeded
cases under the old and the new barrier and compare digests case by case; `SimulatorLaneIT` already does this for
two runs of one build.

## 8. What is not established

- **The split by kind and the number of settle waits per game.** `scripts/capacity-report.sh` prints the sum of the
  timer and not its tags or its sample count, and the lane that produced the figures is gone. The sum per kind and the
  count of `CAUSAL_BARRIER` samples (which equals the count of settle waits) answer section 2 directly: the floor
  predicts about 1.0 s per settle wait in the two barriers combined, and acknowledgements well under that.
- **The hop latency** that sets the expected effect.
- **Whether the early-settle gap in section 4 occurs.** It needs a test that holds the projection back by a few
  milliseconds after a command.

None of these changes the recommendation: the floor exists in the code regardless.

## 9. Follow-up issues

1. **Report the wait split and the settle count** (workbench). `capacity-report.sh` prints `workbench.lane.wait` per
   kind with its sample count, and the report states settle waits per game. Needed to confirm section 2 and to
   measure the effect of 2. Small, and should land first.
2. **Settle on offsets instead of quiet** (workbench, change 1 above). Includes the time budget in place of `max-polls`
   and the old-versus-new digest comparison.
3. **Test the early-settle gap** (workbench). A unit test in which the projection lags a command by one poll while both
   services report drained; it should fail today and pass after 2. If it does not fail, say so in the issue and close it.
4. **Expose applied offsets from read-service** (read-service), only if 2 shows committed offsets are not enough.
5. **Do not issue a broker round trip per checkpoint** (game-service and timeline-service, change 3), after 2 shows
   the cost.
