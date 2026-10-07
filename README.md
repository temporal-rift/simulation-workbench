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
