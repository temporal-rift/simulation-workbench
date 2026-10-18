# Simulator capacity, version 1

Games per hour and memory of the in-process simulator lane, measured on the reference spec at each slot count until
games per hour stopped increasing.

## Method

- **Workload.** Three-player cases of one faction set (`ERASERS`, `PROPHETS`, `WEAVERS`) under policy `random-v1`, one
  rules variant, three cyclic seat rotations per seed. Each run first plays `slots / 3 + 1` unmeasured seeds so the
  services' classes are loaded, then times the measured seeds from the start of the run to the last case's end. Every
  measured case must succeed.
- **Cases per run.** 12 for 1, 2 and 4 slots, then 3 × slots: 24, 36, 48 and 60 for 8, 12, 16 and 20 slots.
- **Builds.** `game-service` at `2a93def1` (branch `fix/in-memory-simulator-hosting`), `timeline-service` at `30c9a2e`
  and `read-service` at `b8bd2c4`, hosted unchanged under profile `in-memory`. The rules are the baseline lane bundle's
  game configuration (`src/test/resources/simulator/baseline-rules.yml`).
- **Procedure.** `SimulatorCapacityIT` with `-Dsimulator.capacity.slots=N -Dsimulator.capacity.seeds=S`,
  `-Dsimulator.builds=<directory of the three jars>` and `-Dsimulator.revisions=<game>,<timeline>,<read>`, once per
  slot count, each in a fresh JVM against a fresh PostgreSQL test container (the workbench's own store; the hosted
  services use none).
- **Reference spec.** 4 cores / 8 threads and 16 GB. The machine is an Intel Core i7-13700H (14 cores, 20 threads) with
  32 GB. The measurement was capped to the spec with processor affinity for 8 logical processors,
  `-XX:ActiveProcessorCount=8` and `-Xmx12g`.
- **Memory.** `peak heap` is the largest heap in use sampled every 250 ms during the measured run; `heap after GC` is
  the heap in use after a full collection when the run ended; `non-heap` is the JVM's non-heap use at the end.

## Result

| Slots | Cases | Wall time (s) | Games per hour | Peak heap (MiB) | Heap after GC (MiB) | Non-heap (MiB) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 12 | 417 | 104 | 3,432 | 2,024 | 620 |
| 2 | 12 | 204 | 212 | 3,458 | 2,031 | 623 |
| 4 | 12 | 106 | 407 | 5,888 | 2,390 | 725 |
| 8 | 24 | 205 | 422 | 6,516 | 4,197 | 1,076 |
| 12 | 36 | 300 | 433 | 9,276 | 6,376 | 1,489 |
| 16 | 48 | 400 | 432 | 10,552 | 8,186 | 1,850 |
| 20 | 60 | 510 | 424 | 11,781 | 10,144 | 2,242 |

Games per hour grows in proportion to slots up to 4 and is flat, about 410 to 430, from 4 slots on: on 8 logical
processors the services' CPU use is the limit, and extra slots only add memory. Each slot adds about 0.5 GiB of live
heap after collection; at 20 slots the 12 GiB cap leaves little room.

## Reading the figures

- A slot starts three services for every case, because a service keeps one case's execution context for its lifetime.
  Starting them dominates a game's wall time; the figures improve when a slot can open the next case's context without
  restarting its services.
- One measurement per slot count, on one machine, with one seed range: treat differences of a few per cent as noise.
- The workload is the smallest case shape. Four- and five-player cases take longer per game.
