# Simulator fidelity

## Simulator fidelity

The search plays on the simulator. A **fidelity check** compares the simulator with the real services: the designer runs
one when they judge that a change could make the simulator play a different game, and nothing in the workbench waits for
one or is refused without one. It plays on the simulator the full case matrix on one seed (3, 4 and 5 players,
every faction set and seat rotation, the policies `faction-greedy` and `random` on every seat) for three variants, then
reproduces every case on one real lane. The variants are the baseline and the two edge variants `low` and `high`, which
set as many parameters as the constraints allow at the minimum and the maximum of their ranges. A new check resolves
them against the newest parameter space with edge variants
([docs/fidelity/current.md](fidelity/current.md)), and records its version on the check, so a stored check keeps the variants it
was accepted with. That is 55 cases per table and variant,
330 in all.

| Operation | Meaning |
|---|---|
| `POST /api/v1/fidelity-checks` | Accepts a check for a `seed` (`simulation:write`, `Idempotency-Key`); `409 FIDELITY_CHECK_ACTIVE` while another is queued or running, `409 SIMULATOR_NOT_AVAILABLE` when the simulator is not enabled or no real lane matches it, or when its baseline rules lack a key the newest parameter space needs (then with `reason` `UNKNOWN_BASELINE`) |
| `GET /api/v1/fidelity-checks`, `.../{id}`, `.../{id}/cases` | The records, newest first, and a check's cases in matrix order, filterable by variant and result |
| `GET /api/v1/simulator/fidelity` | The three source revisions the simulator embeds, and whether a passing record exists for exactly them on the newest parameter space, the one new checks use |

The **real lane** is chosen when the check is accepted: the configured lane with the lowest id that serves the simulator's
content, runs the same source revision of each of the three services, implements the same contract versions, and gives
each seat the same bot player id. Image digests are not compared, because a jar and a container image of one source
revision never share one. A lane is not chosen when any of these differ, and a reproduction placed on it is refused for
the same reason. To make the ids equal, declare the lane's bot player ids (its `bots[].player-id`, in seat order) as
`workbench.simulator.bot-player-ids`; without the key the simulator derives its own, which no real lane has.

A check is a durable process. A worker (`workbench.fidelity.worker-enabled`, default `true`) freezes the experiment over
the simulator's own bundle, starts its run, requests one reproduction per succeeded case on the chosen lane in batches
(`workbench.fidelity.reproduction-batch`), and reads them back as the execution workers finish them. Every request is made
with a key derived from the check, so a restart repeats nothing. A case the simulator could not play is `UNREPRODUCIBLE`,
as is a case whose reproduction could not reach an ending. A check names its experiment and run (`experimentId`, `runId`)
once it begins: its counts stay at zero until that run completes, so the run is where the simulator phase is followed, and
cancelling the run abandons the check, which fails with `RUN_CANCELLED`.

The check **passes** if and only if it has at least one case and every case is `MATCH`; a diverged or unreproducible case, a
run that failed, or a check that could not complete fails it, with no tolerance. A fidelity record stores the seed, both
environments (source revisions, image digests, content digest, contract versions), the three variants with their effective
rules digests and overrides, every case's result with its first divergence or failure, and the verdict.

**The status.** A record applies only to the exact source revisions it ran with. When the simulator embeds a revision no
passing record covers, `established` is `false` until a check passes on the new revisions. The status is information for
the designer; campaigns and confirmations play whatever it says. The operator page of the designer application
(**Simulator fidelity**) shows the status, starts a check from a seed and lists a check's cases with their first divergence. A passing check for the revisions current at a release is
committed as a report under [docs/fidelity](fidelity): the check page offers **Download report**, which renders the served record, with the cases that did not match, as `report-<seed>-<game revision>.md`.
