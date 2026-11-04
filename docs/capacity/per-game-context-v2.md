# Capacity of per-game execution contexts

Games per hour and footprint of one light simulation lane that plays every case in its own execution context,
measured by `scripts/measure-capacity.sh` (README, "Measuring capacity").

## Method

- **Lane.** Exactly one light lane (`temporal-rift-sim-wbe2e-1`) serving the baseline bundle; no second lane project was
  provisioned at any point. It was provisioned by the infrastructure repository's workbench E2E harness with
  `WORKBENCH_E2E_LANES=1`, which also starts the issuer, the lane recycler, an ordinary deployment and the workbench.
- **Workload.** The `baseline-policy-comparison` template with its defaults: seed `42`, every faction set of 3, 4 and
  5 players under all cyclic rotations, policies `random-v1` and `faction-greedy-v1` on every seat, one variant,
  one concurrent case (the lane count). The run was repeated 2 times on the same lane; repetition k plays seed
  42 + k - 1, because a lane keeps the game a case played and replaying the same case on it stalls.
- **Reference spec.** 4 cores / 8 threads and 16 GB. The lane's containers were capped to
  the logical processors `0-7` (`docker update --cpuset-cpus`); their memory limits come from the lane overlay and
  add up to 4640 MiB, within the cap of 16384 MiB.
- **Host.** 13th Gen Intel(R) Core(TM) i7-13700H (14 cores, 20 threads), 32 GB. Docker had 20 logical processors and 16048 MiB of memory.
- **Samples.** `docker stats` for every container of the lane every two seconds during the runs; the heap of a
  JVM is its declared options and the maximum heap the JVM reports.
- **Wall time.** A game's wall time is its attempt's, from its start to its final state; closing its context follows it.

## Build and bundle

The lane images below are not the builds of [simulator-v2.md](simulator-v2.md): their digests differ from both of that
report's lane image sets, and the source revisions they were built from were not recorded, so this rate cannot be tied
to either simulator build.

| Service | Image |
|---|---|
| `game-service` | `sha256:2a118e8f93747b7f3e97ab8cdfd7f894df0e14f223cda9fbb2bc9f0d7f55e964` |
| `timeline-service` | `sha256:a18b28d0666c2d936cf1eb007049073a768f0d34e2ba457af65f07d2b41ac5f7` |
| `read-service` | `sha256:58aee00045440362658d2e557ad986e5c7da57ac513d88a41989b6b3539ead91` |

| | Digest |
|---|---|
| Effective rules | `825d507c86ee2a3d45bb1c18c5cc573229ff6af689e150e747d4a28b768b9b24` |
| Effective content | `bcd07d59ad84792e3d62bae57949ca3884154d2942dbb39f89ab74d913492c4b` |

## Result

| Repetition | Games | Wall time (s) | Games per hour |
|---:|---:|---:|---:|
| 1 | 110 | 1732 | 229 |
| 2 | 110 | 1629 | 243 |

The run-to-run variation between repetitions 1 and 2 is 14 games per hour (6.1% of repetition 1).

### Games measured per player count and policy

| Players | Policy | Games |
|---:|---|---:|
| 3 | `faction-greedy` | 60 |
| 3 | `random` | 60 |
| 4 | `faction-greedy` | 40 |
| 4 | `random` | 40 |
| 5 | `faction-greedy` | 10 |
| 5 | `random` | 10 |

### Wall time per game, in seconds

| Players | Policy | q0.1 | q0.5 | q0.9 |
|---:|---|---:|---:|---:|
| 3 | `faction-greedy` | 11.10 | 13.18 | 15.15 |
| 3 | `random` | 14.14 | 16.29 | 18.60 |
| 4 | `faction-greedy` | 12.55 | 13.22 | 14.40 |
| 4 | `random` | 15.24 | 17.42 | 19.28 |
| 5 | `faction-greedy` | 13.18 | 14.17 | 14.36 |
| 5 | `random` | 12.93 | 17.82 | 19.24 |

### Where the wall time goes

Per repetition, the share of the attempts' wall time and the context closes that went to play (the attempt less
decisions and waits), policy decisions, waits for the services, and closing the contexts.

| Repetition | Play | Policy decisions | Service waits | Context closes | Lane resets |
|---:|---:|---:|---:|---:|---:|
| 1 | 9.2% | 0.1% | 89.6% | 1.2% | 0 |
| 2 | 8.9% | 0.1% | 89.9% | 1.2% | 0 |

Per repetition and kind of service wait: the seconds summed over the attempts, the samples, and the mean seconds per
sample. A settle wait is a causal barrier sample, so the settle waits per game are those samples over the games played.

| Repetition | Kind | Seconds | Samples | Mean per sample (s) |
|---:|---|---:|---:|---:|
| 1 | `ACKNOWLEDGEMENT` | 66.6 | 10960 | 0.006 |
| 1 | `CAUSAL_BARRIER` | 1021.6 | 2640 | 0.387 |
| 1 | `PROJECTION_BARRIER` | 459.2 | 2640 | 0.174 |
| 2 | `ACKNOWLEDGEMENT` | 60.7 | 10721 | 0.006 |
| 2 | `CAUSAL_BARRIER` | 962.4 | 2580 | 0.373 |
| 2 | `PROJECTION_BARRIER` | 439.9 | 2580 | 0.171 |

| Repetition | Games | Settle waits per game |
|---:|---:|---:|
| 1 | 110 | 24 |
| 2 | 110 | 23 |

### Failed attempts by failure code

None.

## Footprint of the lane

| Container | Limit (MiB) | Peak memory (MiB) | Peak CPU (% of one processor) | Declared heap | Maximum heap (MiB) |
|---|---:|---:|---:|---|---:|
| `config-server` | 512 | 309 | 15 | `-Xmx256m` | 256 |
| `game-service` | 1024 | 843 | 150 | `-Xmx512m` | 512 |
| `kafka` | 1024 | 764 | 215 | `-Xms512m -Xmx512m` | n/a |
| `kafka-observer` | 32 | 7 | 1 | `n/a` | n/a |
| `postgres` | 512 | 201 | 46 | `n/a` | n/a |
| `read-service` | 768 | 694 | 118 | `-Xmx512m` | 512 |
| `timeline-service` | 768 | 535 | 62 | `-Xmx512m` | 512 |
