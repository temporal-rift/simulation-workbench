# Experiments and variants

## Experiments

`POST /api/v1/experiments` with an `Idempotency-Key` UUID header freezes a complete
`ExperimentManifest` and returns `201 Experiment` (`experimentId`, `manifest`, `manifestDigest`
SHA-256 hex, `createdAt`). The digest is computed over a canonical form of the manifest, so only a
meaning change (for example score threshold 20 vs 22) changes it — never key order or whitespace.
Experiments are immutable after creation: a configuration change creates a new experiment.

Idempotency: repeating a key with a byte-identical body returns the original experiment; reusing a
key with a changed body returns `409 IDEMPOTENCY_CONFLICT`.

A refusal that is attributable to one manifest value also carries `field`, its path in the manifest (for example
`seeds[1]` or `tables[1].seats[3].policyId`), so a client can place the problem on the input that caused it.

Error codes (RFC 9457 problem details): `400 INVALID_EXPERIMENT` (structural validation, including
duplicate factions, bad counts/seeds, credentials or machine-local paths), `409 MANIFEST_MISMATCH`
(malformed artifact references, image digests, source revisions, or contract pins),
`409 EXPERIMENT_IMMUTABLE` (any edit to a launched manifest), `409 IDEMPOTENCY_CONFLICT`,
`401 UNAUTHORIZED`, `403 INSUFFICIENT_SCOPE`, `404 RESOURCE_NOT_FOUND`.

Retention: the frozen manifest is persisted with its full configuration/content/policy artifacts,
seeds, faction sets, rotations, and execution bounds — never digests alone, and never credentials
or machine-local paths. Every case pins its exact manifest as evidence (see [Evidence, replay, and
reproduction](evidence-and-reports.md#evidence-replay-and-reproduction)).
The baseline rules document of each variant is retained with the experiment, so its
effective rules can always be read back; other resolved external bytes (content bundles, policy artifacts,
service images) are not retained by the runner.

## Variants as parameter overrides

A variant is a baseline rules document plus overrides. `rulesArtifact` names the baseline, which some configured lane
publishes as its `rules-document` (an `EffectiveRules` document); the workbench reads every such document at startup
and refuses to start, naming the lane, when a document's effective rules digest differs from the lane's declared
`rules-digest`. `overrides` maps parameter keys to numbers, or to booleans for a boolean parameter, and `parameterSpaceVersion` names
the space they are validated against. A value of the other type is refused with the reason `WRONG_TYPE`.

The parameter space is one configuration document (`parameter-spaces/parameter-space.json`, described in
`docs/parameter-space/current.md`); its `version` is the revision of its content, and it is read with
`listParameterSpaces` and `getParameterSpace` (any other version is `404`). A campaign search treats a boolean
parameter as a two-value axis. A new campaign uses the served space. Its constraints are expressions in a small notation (comparison
chains over `+`, `-`, `*`, `max` and `and`; a subtraction needs spaces around `-` because keys contain it) that the
workbench both serves and evaluates.

Search budget record version 4 ([docs/budget/v4.md](budget/v4.md)) names search algorithm version 2 with the
search parameters of version 3 ([docs/budget/v3.md](budget/v3.md)): a plan of 21,120 games, sized to a target of
about 4 active hours on the simulator. The target sizes the default plan; it never stops a search. Version 3 also
measured the confirmation, which plays on the simulator with no baseline run
([docs/capacity/confirmation-matrix-v1.md](capacity/confirmation-matrix-v1.md)). Versions 1 to 3 stay unchanged.
The newest record's search parameters are configuration (`search-budgets/v4.json`) and the default `SearchAlgorithm`
values of a new campaign.

Materialising a variant is a pure function of the baseline document, the space and the overrides: every key must be a
parameter, every value in range and on its step; overrides equal to the baseline are dropped; the rest replace the
baseline values and each derived key is set from its source; every constraint is then evaluated, and the effective rules
digest is the SHA-256 of the RFC 8785 serialisation of the properties. The first refusal is reported by key in ascending
order, then constraint id, as `400 INVALID_EXPERIMENT` (or `INVALID_REQUEST` on a preview) with `field`, `reason`
(`UNKNOWN_PARAMETER_SPACE`, `UNKNOWN_BASELINE`, `UNKNOWN_PARAMETER`, `OUT_OF_RANGE`, `OFF_STEP`,
`CONSTRAINT_VIOLATED`) and, for a constraint, `constraintId`.

`POST /api/v1/variant-rules-previews` (`simulation:read`, persists nothing) answers the rules, their digest and the
normalised overrides. Freezing (and previewing) an experiment normalises each variant the same way, sorts its overrides,
and refuses an `effectiveRulesDigest` that differs from the computed one (`409 MANIFEST_MISMATCH`) whenever the
baseline is published; a variant with overrides whose baseline no lane publishes is `UNKNOWN_BASELINE`. The baseline
document of every such variant is retained with the experiment, and `GET /api/v1/experiments/{experimentId}/variants/{label}/rules`
serves the frozen `EffectiveRules`. A case runs only on a lane whose content digest, service provenance and
contract versions equal its variant's effective content digest and pinned services and contracts; the lane's own
rules digest takes no part in the match, because every case opens its context with the variant's frozen effective rules.


## The recording variant

Re-scoring a finished run under another scoring engine is unbiased only on games that play every era: a game that ended
by normal victory stops early, and an engine whose race lasts longer cannot judge it. To record games that play
every era, add one variant to the experiment whose only override is `game.rules.victory.normal-victory: false` and play
it under the priority bots:

```json
{ "label": "recording", "rulesArtifact": "<baseline>", "parameterSpaceVersion": 3,
  "overrides": { "game.rules.victory.normal-victory": false } }
```

Every game of that variant reaches the final era unless the timeline collapses. Games that ended by normal victory under
any other variant truncate whenever the engine's race is longer than the recorded game, so judge an engine on the
recording variant. Playing it needs game-service and the lane bundles to serve the key; a baseline that lacks it is named at
startup and variants on revision 3 are refused until it is served.

## Cohort-matrix preview

`MatrixPreviewService` deterministically enumerates every case coordinate (seed, faction set, seat
rotation, seat table, variant) of a frozen manifest: complete faction sets (10 combinations
at 3 players, 5 at 4, 1 at 5) under `CYCLIC` rotation place every faction in every seat, giving
30 + 20 + 5 = 55 base cases per seed, table, and variant — 220 cases per seed with two tables and two
variants. Seat `i` of a case plays the policy of seat `i` of its table. A manifest frozen before seat tables
(`schemaVersion` 1) keeps its digest and case keys and reads as one uniform table per policy entry, named for the
policy; requests are always `schemaVersion` 2. Ordering is stable and every coordinate carries a deterministic `caseKey`;
the durable runner persists these keys without recomputation.

`POST /api/v1/experiment-previews` (`simulation:write`, no `Idempotency-Key`) previews a manifest without
freezing it: the same validation as `createExperiment`, then `manifestDigest`, `caseCount`, a `breakdown` by
variant and player count, and a page of `cases` (`limit` up to 500, default 100, and `offset`). The digest and
the case keys and order equal those of the experiment frozen from the same manifest and of any run started
from it. Nothing is stored.

## Discovery

Reads for finding and reopening what the workbench holds need `simulation:read`. Lists return `{ items, total }`
and take `limit` (1 to 500, default 50) and `offset`; a missing run for a case list is
`404 RESOURCE_NOT_FOUND`.

| Operation | Path | Order and filters |
|---|---|---|
| `getExperiment` | `GET /api/v1/experiments/{experimentId}` | The experiment exactly as frozen |
| `listExperiments` | `GET /api/v1/experiments` | Newest first |
| `listRuns` | `GET /api/v1/runs` | Newest first; `experimentId`, `state` |
| `listCases` | `GET /api/v1/runs/{runId}/cases` | Matrix order; `state`, `variantLabel` |
| `listComparisons` | `GET /api/v1/comparisons` | Newest first; `runId` (either side). Summaries only: `getComparison` computes the result |
| `listPolicies` | `GET /api/v1/policies` | The bundles a manifest may reference, with `id`, `version`, `artifactDigest`, `description` and `parameters` (none for the baselines) |
| `listLaneBundles` | `GET /api/v1/lane-bundles` | One bundle per distinct effective rules and content digests the configured lanes serve, busy or free, ordered by those digests: artifacts, effective digests, `services`, `contracts` and `laneCount`. No lane identifier, URL or credential |
| `listExperimentTemplates` | `GET /api/v1/experiment-templates` | `baseline-policy-comparison` and `rules-variant-comparison`, each with its `variantCount` and complete `defaults` for every manifest field that does not identify a bundle |

A case summary carries `caseId`, `caseKey`, `runId`, `variantLabel`, `seed`, `playerCount`, `state` and a
nullable `endReason` (present once the case has a result).

### Guided setup

The templates default seeds `["42"]`, player counts `[3, 4, 5]`, all 16 faction sets (10 at 3 players, 5 at 4, 1 at
5), `CYCLIC` rotation and `LOGICAL` time; their `concurrency` is the number of configured lanes, at least 1 and at most
64. Their two remaining bounds are configuration:

| Property | Default | Bound |
|---|---|---|
| `workbench.experiment.templates.case-wall-timeout-seconds` | 3600 | 1 to 86,400; the workbench refuses to start otherwise |
| `workbench.experiment.templates.max-rejected-candidates-per-window` | 10 | 1 to 1,000; the workbench refuses to start otherwise |

A client assembles the `ExperimentManifest` it previews and freezes from a template's defaults, the selected bundles
(each variant's artifacts and effective digests; the manifest's `services` and `contracts` from bundles that share them)
and the designer's edits. A list `limit` or `offset` outside its bounds is `400 INVALID_REQUEST`.
