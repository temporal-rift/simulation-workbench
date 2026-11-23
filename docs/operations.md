# Timing metrics and capacity

## Timing metrics

The workbench publishes Micrometer timers on its Prometheus endpoint (`/actuator/prometheus`) that split a game's wall
time. None carries a case key, seed, game id, player id or credential as a tag.

| Timer | Measures | Tags |
|---|---|---|
| `workbench.case.attempt` | Wall time of one attempt, from its start to its final state | `lane`, `players`, `state` (`SUCCEEDED`, `FAILED`, `CANCELLED`, `INTERRUPTED`), `failure` (failure code, or `none`) |
| `workbench.policy.decision` | Time a policy takes to produce one decision | `policy` (policy id; reproductions use `transcript`) |
| `workbench.lane.wait` | Time spent waiting for the services after a command | `kind` |
| `workbench.lane.context.close` | Duration of closing one case's execution context on a lane's services | `lane`, `outcome` (`CLEAN`, `FAILED`, `TIMEOUT`) |
| `workbench.lane.reset` | Duration of one reset-hook call, which follows a failed close only | `lane`, `outcome` (`CLEAN`, `FAILED`, `TIMEOUT`) |

`workbench.lane.wait` has three kinds. `ACKNOWLEDGEMENT` is one sample per command sent, from the request until the
service answers (terminal readiness sends nothing and adds none). `CAUSAL_BARRIER` and `PROJECTION_BARRIER` are one
sample each per settle wait: the time until both services report their work drained, and the time until every consumer
group has applied the records the event observer has read and every seat's state has been read after that. Each poll's
pause is attributed to the barrier that was still unmet. An attempt whose lane lost its lease or was fenced out is recorded as `INTERRUPTED`.
Slots of the simulator close contexts as a lane does and restart their services in place of a reset hook; these two
timers cover real lanes only, so slots record neither `workbench.lane.context.close` nor `workbench.lane.reset`. A case whose context closes cleanly adds no
`workbench.lane.reset` sample.

## Measuring capacity

`scripts/measure-capacity.sh` measures what one light simulation lane plays when every case opens and closes its own
execution context, and writes the report [docs/capacity/per-game-context.md](capacity/per-game-context.md). The
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
