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

- All service boundaries use generated clients from the published contract modules below. No hand-written
  copy of the game engine or player contract types may be introduced here.
- Bot policies receive only the facts their participant is entitled to at that step. Raw observer evidence,
  opposing credentials, and unearned private data never become policy inputs.
- Operator controls (`simulation:control`) exist only in isolated simulation deployments. Designer access uses
  `simulation:read` / `simulation:write`; observer replay additionally requires `simulation:observe`.

## Requirements

- Java 26+
- Maven 4.0.0-rc-7+
- Docker (for Testcontainers and local infrastructure)
- A `github` server in `~/.m2/settings.xml` with a token holding `read:packages` (shared artifacts resolve
  from GitHub Packages, which needs a token even for public packages)

## Build and test

```bash
mvn test          # unit tests
mvn verify        # unit tests + Failsafe *IT (Testcontainers)
mvn spotless:apply
mvn validate      # all quality gates (formatting + Checkstyle)
```

Java-only loop (skips the designer application's Node download/build/tests once the local repo is warm):

```bash
mvn -o -Dskip.installnodenpm -Dskip.npm test -Dtest=ReportCalculatorTest
mvn -o -Dskip.installnodenpm -Dskip.npm verify -Dit.test=AnalysisIT
```

Integration tests use two parallel forks with one Testcontainers set each; Docker needs at least 4 CPUs and
8 GB. A full `mvn verify` takes about 15 minutes on a 20-thread Windows laptop with Docker Desktop.

## Dependencies

Parent BOM: `temporal-rift-bom`. Contract modules from `apis`, consumed only through generated clients and
types, versions pinned as `<*.version>` properties in `pom.xml`:

| Module | Use |
|---|---|
| `simulation-api` | Workbench boundary: experiments, runs/cases, reports/comparisons, replay/reproduction |
| `simulation-control-api` | Isolated execution boundary: execution context, checkpoints, logical clock |
| `session-event`, `action-event`, `timeline-event`, `scoring-event` | Evidence and result attribution |
| `session-api`, `action-api`, `scoring-api`, `projection-api` | Authenticated participant play and each seat's own game state |

## Application layout

Spring Modulith application (`io.github.temporalrift.workbench`) with hexagonal modules:

| Module | Responsibility |
|---|---|
| `experiment` | Immutable experiment freeze, idempotent creation, deterministic matrix preview |
| `execution` | Durable real-service batches: runs, cases, attempts, leases, reconciliation, cancel/resume, replay, reproduction |
| `policy` | Versioned baseline bot policies |
| `analysis` | Run reports and variant comparisons: attributed metrics, seed-block uncertainty, JSON/CSV exports |

`domain/` is plain Java, `application/` never depends on `infrastructure/`, and modules communicate
only via `ApplicationEvent`. The designer web application lives in `ui/` (React + Vite, served by the
service). For local UI work: `npm run dev` in `ui/` (forwards `/api/` to a running service).

## Authorization

OAuth2 resource server (JWT bearer). Reads require `simulation:read`; mutations require
`simulation:write`; observer replay additionally requires `simulation:observe`.

## Docs

| Guide | Covers |
|---|---|
| [docs/designer-guide.md](docs/designer-guide.md) | Designer UI and the configure → freeze → run → report → compare → inspect → reproduce loop |
| [docs/experiments.md](docs/experiments.md) | Experiments, parameter-override variants, matrix preview, discovery, templates |
| [docs/runs-and-execution.md](docs/runs-and-execution.md) | Durable execution, leases/recovery, lane operation, simulator slots |
| [docs/fidelity-checks.md](docs/fidelity-checks.md) | Simulator-vs-lane fidelity checks |
| [docs/search-campaigns.md](docs/search-campaigns.md) | Balance search campaigns and confirmation |
| [docs/operations.md](docs/operations.md) | Timing metrics and capacity measurement |
| [docs/evidence-and-reports.md](docs/evidence-and-reports.md) | Retained evidence, replay, reproduction, reports and comparisons |
| [docs/bot-policies.md](docs/bot-policies.md) | Baseline bot policies |
| [docs/integration-proof.md](docs/integration-proof.md) | HTTP-boundary integration proof suites |
