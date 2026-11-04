# Simulator capacity, version 2

Games per hour and memory of the in-process simulator lane over the full 110-case matrix with both policies, measured
through the workbench's own run API at 1, 2, 4, 8 and 12 slots, on builds for which
`GET /api/v1/simulator/fidelity` answers `established: true`. The 1, 2, 4 and 8-slot figures were measured on one set of
service builds and the 12-slot figure on a later one (see "Builds"). Version 1 ([simulator-v1.md](simulator-v1.md)) is
unchanged.

## Method

- **Workload.** The `baseline-policy-comparison` matrix: every faction set at 3, 4 and 5 players under all cyclic
  rotations, with `random-v1` and `faction-greedy-v1` on every seat, one variant, logical timing. One seed is 110 cases:
  60 three-player, 40 four-player and 10 five-player. Version 1 played only the three-player cases of one faction set,
  so the two versions' games per hour are not comparable: four- and five-player games take longer.
- **Path.** The campaign path: each run is an experiment created and started through the workbench's REST API, played
  by its case workers on the simulator slots. Version 1 used `SimulatorCapacityIT`, a test run of the lane against a
  test database, not the REST API.
- **Procedure.** For each slot count the workbench was restarted in a fresh JVM, an unmeasured warm-up run was played so
  the services' classes were loaded, and then the measured run played in the same JVM. Wall time is the run's
  `finishedAt` less its `startedAt`, as the workbench records them: the start of the run to the end of its last case.
  Games per hour is succeeded games over that wall time. Every measured case succeeded.
  - At 1 to 8 slots the warm-up played 110 cases (220 at 8 slots).
  - At 12 slots the intended warm-up was interrupted after 9 cases (see "Interruptions"), so a full run of 1,320 cases
    served as the warm-up and the measured run followed it in the same JVM. Both played seeds 9001 to 9012.
- **Measured seeds.** 101 to 103 at 1 slot (330 cases), 101 to 105 at 2 (550), 101 to 107 at 4 (770), 101 to 112 at 8
  (1,320) and 9001 to 9012 at 12 (1,320), each at least 10 minutes of wall time. The warm-ups used seeds 9001 and, at 8
  slots, 9002.
- **Reference spec.** 4 cores / 8 threads and 16 GB, as in version 1. The machine is an Intel Core i7-13700H (14 cores,
  20 threads) with 32 GB. The workbench JVM (Temurin 26.0.2) was capped with processor affinity for 8 logical
  processors (0 to 7), `-XX:ActiveProcessorCount=8` and `-Xmx12g`.
- **Other load.** Nothing else ran on the machine during the 1 to 8-slot measured runs except the idle lane containers
  and a read-only monitor that polled every 20 s. The 12-slot runs shared the machine with a web browser and the
  desktop client, about 2 GB of the 32 GB was free, and total processor load read 87% in a sample taken during the
  measured run. The 12-slot figure is therefore a measurement under heavier machine load than the others.
- **Memory.** `peak heap` is the largest heap in use sampled with `jstat` every 250 ms during the measured run;
  `heap after GC` is the heap in use after a full collection when the run ended; `non-heap` is metaspace plus
  compressed class space plus the code cache at that point. The workbench does not expose JVM memory metrics, so
  non-heap is assembled from those three.

## Builds

Two sets of service builds, each the revisions of a fidelity check that passed (seed 1, 330 of 330 reproductions
matched). The service jars are the same files the simulator hosts; the lane images are built from the same revisions.

| Measurements | Fidelity check | Service | Source revision | Simulator jar digest | Lane image digest |
|---|---|---|---|---|---|
| 1, 2, 4, 8 slots | `a06b3625` | game-service | `7dba986e` | `sha256:72c9827b7284…` | `sha256:7e63b380c470…` |
| | | timeline-service | `010718fd` | `sha256:a15c8b49b9a7…` | `sha256:719da39e9fb7…` |
| | | read-service | `af474c56` | `sha256:c8b726c19e83…` | `sha256:4ece59f4e3e1…` |
| 12 slots and the five-player run | `dfc71430` | game-service | `e1962719` | `sha256:79d9075c721a…` | `sha256:725a9803e739…` |
| | | timeline-service | `010718fd` | `sha256:a15c8b49b9a7…` | `sha256:2f0e915788ee…` |
| | | read-service | `af474c56` | `sha256:c8b726c19e83…` | `sha256:8ecd9af63c59…` |

Between the two sets only game-service changed, by the fix to scoring described under "Stalled games". The workbench
was `main` at `52e6140` for the first set and at `374637f` for the second; the latter makes a lane replay accept a
seat-state snapshot only when two consecutive reads agree, and releases a closed slot router's records (see "Memory
growth").

Contracts: session-event 7.2.0, action-event 9.3.0, timeline-event 7.1.0, scoring-event 3.0.0, session-api 3.0.0,
action-api 7.1.0, scoring-api 2.0.1, projection-api 10.1.2 and chains-api 2.0.0. The rules and content are those of the
provisioned lane (rules digest `825d507c…`, content digest `bcd07d59…`) in both sets.

## Result

| Slots | Cases | Wall time (s) | Games per hour | Peak heap (MiB) | Heap after GC (MiB) | Non-heap (MiB) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 330 | 834.3 | 1,424 | 1,183 | 450 | 244 |
| 2 | 550 | 889.0 | 2,227 | 3,799 | 643 | 283 |
| 4 | 770 | 692.4 | 4,003 | 5,876 | 1,549 | 498 |
| 8 | 1,320 | 753.2 | 6,309 | 8,566 | 2,879 | 730 |
| 12 | 1,320 | 879.6 | 5,402 | 7,681 | 2,467 | 649 |

The 8-slot and 12-slot memory columns are not comparable with the first three: both runs were the second measured run
of their JVM, after about 1,300 to 1,650 further games, and include events the slots retained from the earlier run (see
"Memory growth"). The first 8-slot attempt, which ran in a fresh JVM after its warm-up like the 1 to 4-slot counts,
recorded a peak heap of 5,972 MiB, 1,591 MiB after GC and 498 MiB non-heap. That attempt is not the 8-slot rate: it
stalled (see below).

Games per hour grows with the slot count up to 8: 1.56 times from 1 to 2 slots, 1.80 times from 2 to 4 and 1.58 times
from 4 to 8, which is 78%, 70% and 55% of linear growth from one slot. **From 8 to 12 slots it falls to 0.86 times**,
so the rate has stopped increasing and the method's stop condition is met. The 16-slot count was not measured. The
fall cannot be attributed to the slot count alone: the two points are on different game-service revisions and under
different machine load (see "Other load"). The peak of the rate is at 8 slots, 6,309 games an hour; the lowest figure on
the plateau of 8 to 12 slots, and the only one measured on the later builds, is 5,402.

The peak heap above covers the measured run only. The unmeasured warm-ups, which include class loading, peaked at
4,224 MiB at 1 slot, 4,688 MiB at 2 and 7,183 MiB at 8; the 4-slot warm-up's figure was lost to a recording error. The
12-slot warm-up run peaked at 7,035 MiB and was 2,177 MiB after GC, at 2,856 games an hour.

## Stalled games

On the first set of builds some games never finished. The case worker sat in the lane adapter's settle wait, the loop
that waits for the game and timeline services to report themselves drained and for the read-side projection to settle,
and the case ran until the manifest's 3,600 s case limit unless it was cancelled:

| Slots | Games played | Stalled |
|---:|---:|---:|
| 1, 2 and 4 | 1,980 | 0 |
| 8 | 3,770 | 3 |
| 12 (first set of builds) | 330 | 2 |

The 8-slot figures are one stall in the first 1,320-case attempt and two in the 800-game five-player run; the 8-slot
run reported above had none. Thread dumps taken while games were stalled showed no deadlock and no blocked thread, and
the event router had delivered every record: the services were idle and the settle wait was waiting for something that
did not come.

**The cause was in game-service**: its scoring's completion check never re-resolved outstanding actions, which left an
era unable to complete under load (temporal-rift/game-service#344), fixed by temporal-rift/game-service#345 and
included in the second set of builds. On those builds no game stalled or failed in the 2,640 games of the two completed
12-slot runs and the 800 games of the five-player run ([five-player-analysis-v2.md](five-player-analysis-v2.md)).
The 8-slot rate above is the rate of a run on the first set of builds that happened not to stall; the 12-slot rate is
not subject to that qualification.

## Interruptions

Two runs on the second set of builds were set to `INTERRUPTED` by the workbench; both were resumed or replaced and
neither is part of a reported rate:

- **The 12-slot warm-up**, after 9 cases, about a minute after the run started in a fresh JVM. Several case workers
  inserted the same evidence artifact at the same moment, and the database refused the duplicate key (digest
  `97c09219…`, table `evidence_artifact`). An earlier fresh start at 12 slots, on the first set of builds, was also
  interrupted at its first cases; the cause was not recorded. Not investigated.
- **The five-player run**, after 312 cases, with no failed case and no error in the workbench log. It was resumed and
  completed. Not investigated.

## Memory growth

A slot's event router kept every record it routed and never released them when the slot closed. In the first set of
builds' measurement, with the workbench idle for 47 minutes, a full collection left 2.8 GiB of heap in use: 641,234
retained records across 15 closed routers. The 1 to 8-slot memory figures are therefore those of the build that has
this defect. `temporal-rift/simulation-workbench#126` releases a closed router's records and is part of the 12-slot
build: its run, the second in its JVM, ended with 2,467 MiB after a full collection against 2,879 MiB at 8 slots. While a
slot is open its log still grows with every case it plays; the fix does not trim it.

## Reading the figures

- One measurement per slot count, on one machine: treat differences of a few per cent as noise.
- The measured runs have different numbers of seeds, but each played whole seeds, so every run has the same mix of
  three-, four- and five-player cases.
- The workload is the whole matrix. Its mix of three-, four- and five-player cases is the one a configuration
  search plays, which is why version 1's three-player figure is not the figure to compare with.
