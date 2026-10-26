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
  add up to 4384 MiB, within the cap of 16384 MiB.
- **Host.** 13th Gen Intel(R) Core(TM) i7-13700H (14 cores, 20 threads), 32 GB. Docker had 20 logical processors and
  7996 MiB of memory, so the lane could not have used the 16 GB the spec allows; it used at most 4384 MiB.
- **Samples.** `docker stats` for every container of the lane every two seconds during the runs; the heap of a
  JVM is its declared options and the maximum heap the JVM reports.
- **Wall time.** A game's wall time is its attempt's, from its start to its final state; closing its context follows it.
- **Deviations.** Two repetitions ran as `measure-capacity.sh` describes, except that repetition 2 was started by hand
  after the script stalled on a replayed case (see the note on seeds above), and the lane's game-service ran with its
  per-player rate limit raised to 1,000,000 requests a minute and the issuer put `sub` on client-credentials tokens
  (both fixes to the infrastructure repository's harness, which refused the bots at its default 120 requests a minute
  and without a subject).

## Build and bundle

| Service | Image |
|---|---|
| `game-service` | `sha256:ea24ba144b16a57d95022d19b127235e45f08ef3765e8d5604734b94d102b25b` |
| `timeline-service` | `sha256:7610920dce75fbaa3e6c3638ad6f7028592bcb0d13971f47cd684a967cc238bd` |
| `read-service` | `sha256:59316c6508f53ae6df910db7fae92047a0b29c8fc98a98503d95c76d47651b93` |

| | Digest |
|---|---|
| Effective rules | `825d507c86ee2a3d45bb1c18c5cc573229ff6af689e150e747d4a28b768b9b24` |
| Effective content | `bcd07d59ad84792e3d62bae57949ca3884154d2942dbb39f89ab74d913492c4b` |

The services are the `main` of each repository at the time of the run; the workbench is this change at commit `8a6182f`.

## Result

| Repetition | Games | Wall time (s) | Games per hour |
|---:|---:|---:|---:|
| 1 | 97 | 3933 | 89 |
| 2 | 95 | 3880 | 88 |

The run-to-run variation between repetitions 1 and 2 is 1 game per hour (1.1% of repetition 1).

### Games measured per player count and policy

| Players | Policy | Games |
|---:|---|---:|
| 3 | `faction-greedy` | 59 |
| 3 | `random` | 59 |
| 4 | `faction-greedy` | 32 |
| 4 | `random` | 34 |
| 5 | `faction-greedy` | 4 |
| 5 | `random` | 4 |

### Wall time per game, in seconds

| Players | Policy | q0.1 | q0.5 | q0.9 |
|---:|---|---:|---:|---:|
| 3 | `faction-greedy` | 28.19 | 33.47 | 42.91 |
| 3 | `random` | 39.55 | 44.31 | 49.49 |
| 4 | `faction-greedy` | 32.24 | 37.38 | 40.00 |
| 4 | `random` | 39.59 | 46.59 | 50.58 |
| 5 | `faction-greedy` | 38.38 | 38.88 | 40.62 |
| 5 | `random` | 51.29 | 51.85 | 52.68 |

### Where the wall time goes

Per repetition, the share of the attempts' wall time and the context closes that went to play (the attempt less
decisions and waits), policy decisions, waits for the services, and closing the contexts.

| Repetition | Play | Policy decisions | Service waits | Context closes | Lane resets |
|---:|---:|---:|---:|---:|---:|
| 1 | 3.4% | 0.0% | 96.1% | 0.5% | 0 |
| 2 | 3.2% | 0.0% | 96.3% | 0.5% | 0 |

No lane reset ran in either repetition: every context closed cleanly, at about 0.2 s each.

### Failed attempts by failure code

| Failure code | Attempts |
|---|---:|
| `EXECUTION_FAILED` | 28 |

All 28 read a seat's game state right after the game started, before the read side held the game (`404` for the
player, or `503 Game state is not ready`), and the manifest allows one attempt per case. The runner now waits for the
projection instead of failing the attempt; the figures above predate that change, so they include those failures but
no games they would have added.

## Footprint of the lane

| Container | Limit (MiB) | Peak memory (MiB) | Peak CPU (% of one processor) | Declared heap | Maximum heap (MiB) |
|---|---:|---:|---:|---|---:|
| `config-server` | 512 | 204 | 15 | `-Xmx256m` | 256 |
| `game-service` | 768 | 768 | 64 | `-Xmx512m` | 512 |
| `kafka` | 1024 | 834 | 213 | `-Xms512m -Xmx512m` | n/a |
| `kafka-observer` | 32 | 5 | 0 | `n/a` | n/a |
| `postgres` | 512 | 206 | 47 | `n/a` | n/a |
| `read-service` | 768 | 682 | 64 | `-Xmx512m` | 512 |
| `timeline-service` | 768 | 521 | 40 | `-Xmx512m` | 512 |

## Reading the figures

- A game takes 28 to 53 s, almost all of it waiting for the services to settle (96% of the wall time); the policy
  decides in well under a millisecond and play itself is about 3%.
- Closing a context costs about 0.2 s, 0.5% of a game, and nothing else is spent between cases: no lane was restarted.
- One lane plays about 88 games an hour at this workload; the two repetitions differ by 1.1%.
- `game-service` peaked at its 768 MiB limit with a 512 MiB heap, so it has no headroom left.
