# Temporal Rift Simulation Workbench

Designer product for reproducible Temporal Rift experiments: freeze an experiment, run it against real
services in isolated lanes, compare rules variants with honest statistics, inspect individual cases, and
reproduce a saved case. Gameplay stays authoritative in `game-service` and `timeline-service`; bots use the
same authenticated participant commands and entitled participant views as human players.

## Ownership

This repository owns experiment management, bot policies, durable execution, evidence, analytics, and the
designer UI. It does not own game rules or outcome resolution, event-sourced timeline state, read
projections, contract publication, lane provisioning, or the human player's client:

| Responsibility | Owner |
|---|---|
| Game sessions, actions, scoring rules | `game-service` |
| Weighted outcomes, resolution, paradox handling | `timeline-service` |
| Participant projections and history | `read-service` |
| Published contract modules | `apis` |
| Isolated runtime lanes and deployed verification | `infrastructure` |
| Human gameplay client | `game-client` |
| Experiment/run/policy/report/replay/reproduction behavior | this repository |

Research orchestration lives here, never in a gameplay service. Observer capabilities live here, never in
the human player's client.

## Boundaries

- All service boundaries use generated clients and interfaces from the published contract modules listed
  below. No hand-written copy of the game engine or player contract types may be introduced in this
  repository.
- Bot policies receive only the facts their participant is entitled to at that step. Raw observer evidence,
  opposing credentials, and unearned private data never become policy inputs.
- Operator controls (`simulation:control`) exist only in isolated simulation deployments. Designer access uses
  `simulation:read` / `simulation:write`; observer replay additionally requires `simulation:observe`.

## Requirements

- Java 26+
- Maven 4.0.0-rc-7+
- Docker (for Testcontainers and local infrastructure)
- A `github` server in `~/.m2/settings.xml` holding your GitHub username and a token with `read:packages`: shared
  artifacts resolve from GitHub Packages, which needs a token even for public packages. The registry itself is declared
  in `.mvn/settings.xml`. To build the image, pass the settings as a secret:
  `docker build --secret id=maven_settings,src=$HOME/.m2/settings.xml .`

## Build and test

```bash
# All tests (unit + integration via Testcontainers)
mvn test

# Full verification (also runs Failsafe *IT plus Sonar analysis with SONAR_TOKEN set)
mvn verify

# Check and fix formatting
mvn spotless:apply

# Run all quality gates (formatting + Checkstyle)
mvn validate
```

## Dependencies

- Parent BOM: `temporal-rift-bom` (Spotless, Checkstyle, OpenAPI generator)
- Published contract modules from `apis`, consumed only through generated clients and types, versions pinned as
  `<*.version>` properties in `pom.xml`:

| Module | Use |
|---|---|
| `simulation-api` | Workbench boundary: experiments, runs/cases, reports/comparisons, replay/reproduction |
| `simulation-control-api` | Isolated execution boundary: execution context, checkpoints, logical clock |
| `session-event`, `action-event`, `timeline-event`, `scoring-event` | Evidence and result attribution |
| `session-api`, `action-api`, `scoring-api` | Authenticated participant play |

## Application layout

Spring Modulith application (`io.github.temporalrift.workbench`) with hexagonal modules:

| Module | Responsibility |
|---|---|
| `experiment` | Immutable experiment freeze, idempotent creation, deterministic matrix preview |
| `execution` | Durable real-service batches with interruption recovery (later package) |
| `policy` | Versioned baseline bot policies (later package) |
| `analysis` | Balance comparisons with truthful statistics and exports (later package) |

`domain/` is plain Java, `application/` never depends on `infrastructure/`, and modules communicate
only via `ApplicationEvent`. All service boundaries are generated from the pinned contract modules;
no hand-written copy of engine or player-contract types may be introduced here.

## Authorization

OAuth2 resource server (JWT bearer). Reads require `simulation:read`; mutations require
`simulation:write`; observer replay additionally requires `simulation:observe`.

## Experiments (W1)

`POST /api/v1/experiments` with an `Idempotency-Key` UUID header freezes a complete
`ExperimentManifest` and returns `201 Experiment` (`experimentId`, `manifest`, `manifestDigest`
SHA-256 hex, `createdAt`). The digest is computed over a canonical form of the manifest, so only a
meaning change (for example score threshold 20 vs 22) changes it — never key order or whitespace.
Experiments are immutable after creation: a configuration change creates a new experiment.

Idempotency: repeating a key with a byte-identical body returns the original experiment; reusing a
key with a changed body returns `409 IDEMPOTENCY_CONFLICT`.

Error codes (RFC 9457 problem details): `400 INVALID_EXPERIMENT` (structural validation, including
duplicate factions, bad counts/seeds, credentials or machine-local paths), `409 MANIFEST_MISMATCH`
(malformed artifact references, image digests, source revisions, or contract pins),
`409 EXPERIMENT_IMMUTABLE` (any edit to a launched manifest), `409 IDEMPOTENCY_CONFLICT`,
`401 UNAUTHORIZED`, `403 INSUFFICIENT_SCOPE`, `404 RESOURCE_NOT_FOUND`.

Retention: the frozen manifest is persisted with its full configuration/content/policy artifacts,
seeds, faction sets, rotations, and execution bounds — never digests alone, and never credentials
or machine-local paths. Resolved external bytes (rules bundles, policy artifacts, service images)
land with the durable runner and evidence store in later packages.

## Cohort-matrix preview (W1)

`MatrixPreviewService` deterministically enumerates every case coordinate (seed, faction set, seat
rotation, policy assignment, variant) of a frozen manifest: complete faction sets (10 combinations
at 3 players, 5 at 4, 1 at 5) under `CYCLIC` rotation place every faction in every seat, giving
30 + 20 + 5 = 55 base cases per seed, policy, and variant — 220 cases per seed with two homogeneous
policies and two variants. Ordering is stable and every coordinate carries a deterministic `caseKey`;
the durable runner persists these keys without recomputation. No REST preview endpoint exists in the
published boundary, so preview is a domain service covered by unit tests, not a controller.
