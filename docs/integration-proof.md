# Application integration proof

## Application integration proof

The `proof` test package (`src/test/java/io/github/temporalrift/workbench/proof`) proves the capabilities work
together through the published HTTP boundary only: every step (freeze, preview, run, cases, report, comparison,
replay, reproduction, resume) is a request with a signed bearer token, against real PostgreSQL and Kafka
Testcontainers. Only the lane behind `LaneProvider` is scripted in-process; real game, timeline and read services
and the operator-control endpoints belong to their own repositories, and the deployed run is orchestrated by
`infrastructure`. A green run here says the workbench accounts for, reports and reproduces what its lane returns
truthfully; it says nothing about game balance.

| Suite | Proves |
|---|---|
| `CoverageMatrixIT` | The seed-42 matrix (all faction sets of 3, 4 and 5 players, `CYCLIC` rotations, both baseline policies, two rules variants) is 220 distinct coordinates, 55 per variant and policy; each is listed with an authoritative ending and final scores or an explicit failure code and reason; one seed claims no interval |
| `MultiSeedReportingIT` | Ten eligible Erasers samples with four wins, one shared, report and export `wins = 4`, 40% and 10%; one block states `INSUFFICIENT_SAMPLE` with no interval; a failed counterpart is listed and left out of the paired estimate; JSON and CSV repeat byte for byte |
| `ReproductionProofIT` | A saved case reproduces to `MATCH` and adds no sample; a tampered or missing pinned input returns `409 MANIFEST_MISMATCH` and creates no reproduction |
| `RecoveryProofIT` | A 1000-case run interrupted after 400 successes keeps those results unchanged on resume and never exceeds 1000 successes |
| `PerspectiveProofIT` | A `PLAYER` replay hides opposing hands and unearned facts; `OBSERVER` needs `simulation:observe`; forged observer-only evidence does not change a bot's decisions |
| `AccessBoundaryIT` | Tokens with only participant or `simulation:control` authority get `403` on every designer, report, replay and reproduction operation, and learn nothing |
| `HttpCaseLaneTest` | A lane answering `404 SIMULATION_CONTROL_UNAVAILABLE` or `403 SIMULATION_CONTROL_FORBIDDEN` fails the attempt with that code |
| `GameplaySeparationTest` | Gameplay services are reached only through generated clients in the lane adapter, no gameplay-service code is shared, and policies depend on no evidence or analysis types |

Run them with the rest of the integration suite (`mvn verify`), or alone:

```bash
mvn test-compile failsafe:integration-test failsafe:verify -Dit.test='CoverageMatrixIT,MultiSeedReportingIT,ReproductionProofIT,RecoveryProofIT,PerspectiveProofIT,AccessBoundaryIT'
mvn test -Dtest='HttpCaseLaneTest,GameplaySeparationTest'
```

### Evidence files

`MultiSeedReportingIT` and `ReproductionProofIT` write `target/simulation-evidence/multi-seed-sample.json` and
`reproduction.json`. Both are assembled from API responses only (the experiment, run, report and case reads), so a
deployed run can produce the same documents from the same endpoints. They hold no timestamp and no credential, and
the same inputs give the same bytes.

| Field | Meaning |
|---|---|
| `formatVersion` | Layout version, currently `1` |
| `kind` | `MULTI_SEED_SAMPLE` or `EXACT_REPRODUCTION` |
| `experimentId`, `manifestDigest` | The frozen experiment and its digest |
| `timingMode`, `services`, `contracts`, `policies`, `variants` | The manifest's logical timing, pinned service images and revisions, contract versions, policy ids, versions and artifact digests, and each variant's effective rules and content digests |
| `runId`, `analysisVersion`, `analysisSeed`, `complete`, `caseCounts`, `cohorts`, `cases` | (`MULTI_SEED_SAMPLE`) the report with independent-block counts and intervals, and each case's key, variant, seed, state and semantic digest |
| `caseId`, `caseKey`, `savedSemanticDigest`, `reproductionId`, `state`, `firstDivergence` | (`EXACT_REPRODUCTION`) the saved case and how its re-execution ended: `MATCH` means the semantic digests are equal |
