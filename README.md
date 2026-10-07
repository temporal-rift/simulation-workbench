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

## Published contracts consumed

| Module | Version | Use |
|---|---|---|
| `simulation-api` | `1.0.0` | Workbench boundary: experiments, runs/cases, reports/comparisons, replay/reproduction |
| `simulation-control-api` | `1.0.0` | Isolated execution boundary: execution context, checkpoints, logical clock |
| `session-event` / `action-event` / `timeline-event` / `scoring-event` | `7.0.0` / `9.3.0` / `7.1.0` / `3.0.0` | Evidence and result attribution via generated types |
| `session-api` / `action-api` / `scoring-api` | `3.0.0` / `7.1.0` / `2.0.1` | Authenticated participant play via generated clients |

Pinned versions live as `<*-version>` properties in `pom.xml`, following the sibling-service convention.

## Repository baselines

- **License:** GNU General Public License v3.0, declared in `pom.xml` like the sibling services.
- **Branch protection:** the `Temporal Rift Checks` ruleset guards the default branch (linear history,
  signed commits, required `SonarCloud Code Analysis`, `build-test-analyze`, and `format-check` checks;
  branch deletion blocked).
- **CI:** `.github/workflows/ci.yml` runs `format-check` (`mvn validate`), `security-snyk` (high severity,
  `temporal-rift` org), and `build-test-analyze` (`mvn verify` plus SonarCloud analysis) on pull requests
  and pushes to `main`.
- **Security:** Dependabot (daily Maven, organization contract artifacts grouped), Snyk policy file,
  SonarCloud project `temporal-rift_simulation-workbench`, CodeRabbit review on the `review` label.
- **Container:** root `Dockerfile` follows the sibling-service multi-stage Maven build with a non-root
  runtime user and `/actuator/health` health check.

## Delivery backlog

System-wide coordination stays in the infrastructure epic for the simulation and balance workbench. The
seven workbench-owned delivery packages are tracked as issues in this repository, in implementation order:

| Order | Issue | Focus |
|---|---|---|
| 1 | [Freeze experiments and preview the cohort matrix](https://github.com/temporal-rift/simulation-workbench/issues/3) | Attributable experiments and exact case matrix |
| 2 | [Play all decision windows with versioned baseline policies](https://github.com/temporal-rift/simulation-workbench/issues/2) | Explicit baseline strategies without privileged information |
| 3 | [Run durable real-service batches with interruption recovery](https://github.com/temporal-rift/simulation-workbench/issues/6) | Launch, cancel and resume without losing completed work |
| 4 | [Retain evidence and reproduce a saved case](https://github.com/temporal-rift/simulation-workbench/issues/5) | Per-case evidence plus clean-lane reproduction |
| 5 | [Compare balance variants with truthful statistics and exports](https://github.com/temporal-rift/simulation-workbench/issues/4) | Attributable populations, uncertainty, and exports |
| 6 | [Deliver the designer UI and perspective-safe replay inspector](https://github.com/temporal-rift/simulation-workbench/issues/7) | Full research workflow without hand-operated endpoints |
| 7 | [Prove the application workflow while preserving ordinary gameplay](https://github.com/temporal-rift/simulation-workbench/issues/1) | Integrated proof with gameplay regressions green |

Foundations (this provisioning, plus both published contract modules) come first; execution inputs and the
experiment model next; then playable experiments; then the research product; then milestone acceptance. Test
and documentation obligations land with their own package — never deferred to the last wave.

## Requirements

- Java 26+
- Maven 4.0.0-rc-7+
- Docker (for Testcontainers and local infrastructure)
- A `github` server in `~/.m2/settings.xml` holding a GitHub username and a token with `read:packages`:
  shared artifacts resolve from GitHub Packages, which needs a token even for public packages.

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

## Contributing

- Create a focused branch from current `origin/main` and open a pull request that links its issue.
- Keep the hexagonal layout (`domain/` plain Java, `application/` independent of `infrastructure/`,
  Modulith modules communicating only via `ApplicationEvent`) once the experiment package establishes the bootstrap.
- Add the `review` label when a change needs non-trivial reasoning to judge correct (concurrency,
  ordering, recovery, statistical dependence); leave routine changes unlabeled.
- Every behavior change ships with its tests and documentation in the same package.
