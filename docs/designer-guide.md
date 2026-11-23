# Designer guide

## Designer web application

`ui/` is a React, TypeScript and Vite application that the service serves on its own origin: `/` and its client-side
routes (`/experiments`, `/runs`, and deeper links to them) return the application to a browser, its built assets are
under `/assets/`, and everything it reads goes through the bearer-protected `/api/`, which never answers with the
application. The Maven build installs Node, installs the dependencies when `ui/package-lock.json` changed, generates the
client from the pinned contract, runs lint, type checking and the component and accessibility tests, and copies the
build into `static/` of the jar.

A designer signs in with the identity provider whose tokens the service accepts (authorization code with PKCE; tokens
are kept in memory only). The application offers each action according to the granted scopes and shows an action it
may not take as unavailable, naming the missing scope; the service remains the authority on every request.

| Property | Environment variable | Meaning |
|---|---|---|
| `workbench.ui.oidc-issuer-url` | `JWT_ISSUER_URI` | Issuer the browser signs in with; defaults to the issuer that validates bearer tokens |
| `workbench.ui.oidc-client-id` | `WORKBENCH_UI_OIDC_CLIENT_ID` | Public client identifier (default `simulation-workbench-ui`); its redirect URI is `<origin>/auth/callback` |
| `workbench.ui.oidc-audience` | `WORKBENCH_UI_OIDC_AUDIENCE` | Audience requested at sign-in, when the issuer needs one |

The browser reads this at `/config.js`. Responses carry a content security policy that lets the application load
only its own scripts and styles and call only this service and the issuer.

What a designer can do:

- Configure an experiment, optionally from a template (variants, each running a bundle chosen from those the lanes
  serve, catalog policies with their seats, seeds, player counts, faction sets, timing and execution bounds), preview the exact case matrix the service would freeze, and freeze it only after
  confirming the shown digest. An unsent draft is kept in the tab's session storage and survives an expired session.
- Open a frozen experiment, which is read-only, and start a new draft from its manifest.
- Launch a run and follow its state and counts while it is open; cancel while it is cancellable and resume from
  `INTERRUPTED`; read each failure with its code and message and open the failed case. Pending, running, failed and
  cancelled cases are never shown as results and incomplete data is marked partial.
- Read a run's report (every cohort with its denominators, independent blocks, intervals or the reason there is none,
  and unknown counts; marked partial while any requested case has not succeeded) and export its JSON or CSV exactly as
  the service returns it. Compare two variants and read the declared differences, matched blocks, excluded pairs and
  paired differences; runs the service refuses as incomparable are shown with the differences it names.
- Open a case with its seats, every attempt and failure, and its result or the plain statement that it has none.
- Inspect a case's replay from one perspective at a time (see "Perspectives" below), and request a reproduction of a
  saved case and follow it to a match or its first divergence.

For local work, `npm run dev` in `ui/` serves the application with hot reload and forwards `/api/` and `/config.js`
to a running service (`WORKBENCH_SERVICE_URL`, default `http://localhost:8080`). The typed client in
`ui/src/api/generated` is not versioned: the Maven build generates it with `npm run generate:api` from the contract
version pinned in `pom.xml` (`simulation-api.version`, unpacked by Maven into `target/dependency-specs`). After a
Maven build, or after bumping the pin, run `npm run generate:api` before `npm run dev`.

## Designer guide

A worked pass through the whole loop, from a configured experiment to a reproduced case. Each step names the page
and what to expect; the sections after it say what the service does underneath.

### Before you start

Bring up the dependencies the `dev` profile expects (PostgreSQL on 5432, Kafka on 9092, an OIDC issuer on 9000) and
start the service from this repository:

```bash
docker compose -f ../infrastructure/compose.yml up -d postgres kafka kafka-topics identity-provider
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

`spring-boot:run` builds the designer application and serves it from `static/`; with `-Dskip.npm` there is no
application to load at <http://localhost:8080>, only the API.

Open <http://localhost:8080> and choose **Sign in**. The local issuer accepts any username and grants the scopes the
application asks for, so everything below is available. Cases execute only on configured isolated lanes serving their
variant's rules and content (see [Operating the runner](runs-and-execution.md#operating-the-runner)); a run whose variants are not all served is refused when it is
started.

### 1. Configure and preview

**Experiments → New experiment.** Optionally **Start from** a template: *Baseline policy comparison* (one variant, the
faction-greedy and random policies each on every seat of a table of its own) or *Rules variant comparison* (two variants,
the random policy on every seat of one table). A template fills the variant slots with the bundles the lanes serve, in order, and sets seeds `42`, player
counts 3, 4 and 5, every faction set, and the execution bounds; every value stays editable and your experiment name is
kept. Then name the experiment, label each variant and choose its **Bundle** from those the configured lanes serve. The
bundle's artifacts and effective digests, and the service images and contract versions it pins, are shown read-only:
nothing that identifies a bundle is typed. Variants are comparable only when their bundles pin the same services and
contracts; otherwise the page names the differing fields and the draft cannot be previewed or frozen. Pick bot policies
from the catalog, then define seat tables: each has an id and one of those policies for every seat, so a table can seat
one policy everywhere or mix them; and set seeds, player counts, faction sets and the execution bounds. If
no lane is configured the page says so and nothing can be frozen. A value the service refuses is marked on its field,
and your unsent draft survives a reload and an expired session.

Press **Preview matrix**. The page shows the exact case count, the count per variant, table and player count, and a browsable
page of case coordinates, all as the service computed them. Nothing is stored yet.

### 2. Freeze

Press **Freeze this experiment…**, read the digest and case count in the confirmation, and press **Confirm and
freeze**. A frozen experiment is read-only; **Change: new draft from this experiment** starts a new draft from its
manifest and leaves the original untouched.

### 3. Launch and follow a run

On the frozen experiment press **Launch a run**. The run page shows its state and counts (requested, pending, running,
succeeded, failed, cancelled) and refreshes while the run is open and the tab is visible. Cancel is offered while the
run can still be stopped and resume only from `INTERRUPTED`. Failed cases are listed with their code and message and
link to the case. Anything that has not succeeded is marked **Partial** and is never counted as a result.

### 4. Read the report

Open **Report** on the run. Each cohort (variant, table, player count, faction set, seat) shows its eligible games and
independent blocks, then every metric with its value, numerator and denominator, and its interval. A metric supported
by fewer than two independent blocks shows its value with **Insufficient sample** and no interval; a metric with a
denominator of 0 shows **No data**; opportunities whose playability is unknown are counted beside it, not in it.
**Export JSON** and **Export CSV** download the bytes the service returned, unchanged.

#### Evaluate a variant

The **Evaluation** panel on the run page judges one variant against the balance criterion. Choose the variant, the
criterion revision, and the mode (screening ranks and discards candidates; confirmation may confirm). The panel shows the
verdict, why a run is not valid evidence when it is not, the score, and in confirmation mode how much of the
confirmation sample the run has. The table lists every evaluated line with its cohort, value, interval, band,
deviation, and verdict, and can be filtered to failing or inconclusive lines. The panel always states that bot results
say nothing about balance among human players.

### 5. Compare two variants

**Comparisons → Compare two variants.** Enter the run and variant label of the baseline and of the candidate (a run
can be both sides when it holds both variants). The comparison lists the definitions the two sides declare as
different, the matched blocks, the pairs it had to exclude (a side failed or is unfinished) and each paired
difference with its interval. If the sides differ in anything that changes gameplay beyond the declared variant, the
service refuses the comparison and the page names the differences.

### 6. Inspect a case

From the run page open a case. It shows the seats with their faction and policy, every attempt with its state, game and
failure, and the result (end reason, winners, final scores, eras, rounds, decisions and semantic digest) or states that
the case has none.

#### Perspectives

**Replay** shows a case from one perspective at a time:

- **View as player** shows only the steps of the chosen seat and only what that seat was entitled to know at each step:
  never an opponent's hand or faction, and exact outcome weights only from the step at which the seat earned them. Step
  numbers belong to the whole case, so a seat's steps leave gaps where other seats acted. **Later steps** and
  **Earlier steps** page through the case.
- **View as observer…** needs the `simulation:observe` scope. It is labelled **Observer evidence**: every seat's steps,
  then the raw events, one list per source in the order that source delivered them (no order across sources exists).
  Without the scope the page says observer access was denied and shows nothing. Observer evidence is for inspection
  only and never feeds a bot policy.

Choosing another seat or switching perspective discards the previous view's data from the page and from the
application's cache before anything else is requested.

### 7. Reproduce a case

On the case page press **Reproduce this case**. A reproduction is a check of the saved case, never a new sample.

| You see | It means |
|---|---|
| Waiting, queued behind other work | The lane that played the case is busy or serves other rules or content; the reproduction starts when it is free and serving this case's variant again. This is not a failure. |
| Running | The case is being replayed against the pinned services. |
| Match | Every accepted decision and the final result equal the recording. |
| Diverged at step *n*, kind *k* | The first difference, with the expected (recorded) and actual values. Kinds are `OBSERVATION`, `DECISION`, `WINDOW`, `MISSING_STEP`, `UNEXPECTED_STEP`, `UNEXPECTED_WINDOW`, `COMMAND_REJECTED` and, after the last step, `END_REASON`, `WINNERS`, `FINAL_SCORES`, `ERAS`, `SEMANTIC_DIGEST`. |
| The reproduction failed | It could not be completed this time. This says nothing about the case; **Try again** requests a new one. |
| Cannot be reproduced | The case has no saved result or transcript, or its pinned configuration no longer matches (`409 MANIFEST_MISMATCH`). |

### When something goes wrong

Every refusal is shown as a plain sentence with a way to continue. If the service cannot be reached, the last data stays
on screen marked stale with **Retry**, and nothing you entered is lost. A refused action names the scope your session
lacks. Progress, errors and denials are announced to screen readers, and every control is operable from the keyboard.
