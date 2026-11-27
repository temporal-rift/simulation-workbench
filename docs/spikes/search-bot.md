# Spike: how to build a search bot that hunts exploits

Answers [simulation-workbench#198](https://github.com/temporal-rift/simulation-workbench/issues/198), under
[game-service#381](https://github.com/temporal-rift/game-service/issues/381).

**Verdict: build option (c), a strategy-level best response, and keep (a) to (d) as the record of what was weighed.**
One seat keeps the priority-bot structure but its preference table and decision thresholds, including the cards the
standard bots ignore, become parameters searched with ordinary games against the standard bots. It needs no change to
any service or contract, it is the only option whose information play is judged by real outcomes, and a hunt starting
with the five-player tables costs **about 4.2 server hours** at 12 slots (26,480 screening games at about 6,300 games
an hour), plus about 0.1 hour for each confirmed cell. The figure is a speed goal, not a cap.
Its limit is that it finds strategies a preference table can express, not conditional combinations. The rule owner
decides; the draft implementation issue is in section 7.

**No hunt runs now.** The hunt is meant to run once the new rules are implemented. Hunting with today's rules would spend
the hours on rules that are going to change. This spike only decides how the hunt is done and what it costs.

Revisions read: simulation-workbench `34c4b1b`, `simulation-control-api` 2.4.0. Nothing in this spike is a new
measurement: the costs in section 1 are the repository's published capacity reports, and every statement marked
*estimate* follows from them by the arithmetic shown. What is not established is listed in section 8.

## 1. Measured costs

How they were measured: [per-game-context-v2](../capacity/per-game-context-v2.md) plays the 110-case matrix twice on one
light lane (the real services over Kafka) and records every command acknowledgement and every settle wait;
[simulator-v2](../capacity/simulator-v2.md) plays the same matrix through the run API on the in-process simulator at 1, 2,
4, 8 and 12 slots; [the light-lane spike](light-lane-waits.md) explains what a settle wait is.

| Quantity | Light lane (one lane) | Simulator, 1 slot | Simulator, 12 slots |
|---|---:|---:|---:|
| One full game | 13 to 18 s (median, by table size and bot) | 2.5 s (1,424 games/h) | 7 to 8 s per slot (5,402 games/h; about 20,000 games in 3.3 h) |
| Commands per game | about 99 (10,960 acknowledgements over 110 games) | same game | same game |
| One command (a game over its commands, all seats) | about 160 ms | about 25 ms | about 70 ms |
| Settle waits per game | 23 to 24 | not recorded | not recorded |

Where a game's time goes, on the light lane (the only place the split was recorded):

| Share of a game | Lane |
|---|---:|
| Waiting for the services (causal and projection barriers, acknowledgements) | 89.6% to 89.9% |
| Playing (the attempt less decisions and waits) | 8.9% to 9.2% |
| Policy decisions | 0.1% |
| Closing the execution context | 1.2% |

A settle wait costs 0.387 s (causal barrier) plus 0.174 s (projection barrier), so the 24 waits of a game are about
13.5 s of its 13 to 18 s. An acknowledgement is 6 ms. The policy is negligible: a bot decision is well under a
millisecond, so no option below is limited by the bot, only by how many games the services can play.

Reading the table: the 100 commands per game are counted over every seat of the game, three to five of them, and include
rejected candidates, which are also acknowledged commands. One seat therefore makes about 100 / N of them, about 25 at a
four-player table (33 at three seats, 20 at five), and that is the number of decisions a search of one seat has to make.
The rollouts of a search still play every seat, so a rollout costs a whole game's commands. The simulator removes Kafka, the outbox and the
database, which is why it plays a game in a fifth to a half of the lane's time. Slots share one machine, so a game takes
longer the more run at once: the 12-slot rate is 3.8 times the 1-slot rate for 12 times the slots. The cost basis below
is **7 to 8 s per game per slot and 5,400 games an hour at 12 slots**, the rate the confirmation matrix already uses.

## 2. The options

Costs are *estimates* unless stated. They are for the same hunt as option (c) (section 6): the five-player tables, five
factions, 300 searched games and 300 reference games per faction. They use about 20 decisions of the searched seat per
game (100 commands over five seats), and a rollout or a game costs all of its seats' commands. Using the game-wide 100
as the searched seat's decisions would be a conservative upper bound, five times higher for (a), (b) and (d).

### (a) Move-level search through the real services

At each decision, try each candidate action, play the game on to its end several times, keep the best.

**What it requires.** The services cannot be forked: `simulation-control-api` can configure and close a context,
read a checkpoint and advance a logical clock, and nothing else. Two ways to get a game back to "the state at decision
k":

1. *Fork or snapshot in `simulation-control-api`*: a new operation on each of the three services to capture and restore
   a context (gameplay state, timers, pending outbox, consumer positions on the lane). That is a minor version of the
   contract, three service implementations, and a restore that must be proven to leave a game indistinguishable from one
   never forked. The simulator has no broker, so it is the easier backend, but the services still hold state in memory
   and a database.
2. *Replay the case's evidence up to the decision.* This already exists: a saved case can be replayed decision by
   decision (the reproduction runner), and equal digests show the replay reaches the same state. It needs no contract
   change. A prefix of `k` decisions costs `k/100` of a game.

**A trap in the replay route.** A game's chance outcomes come from its seed, so a replay to decision k with the same seed
reproduces the same future. A rollout from there sees the true outcome of every event still to resolve, which no seat is
entitled to know. Valid rollouts must change the seed after the decision point, which the contract does not offer (the
seed is part of the context, set when it opens) and which would change the prefix as well. So (a) through replay is
either a search with perfect knowledge of the chance outcomes, which overstates strength and is not a valid exploit
hunt, or it needs the same new capability as the fork: "continue this game under another entropy stream".

**Cost.** With a fork, a rollout costs the rest of the game, on average half a game, and a rollout from the start costs a
whole game. With 5 candidates a decision and 20 rollouts each, one decision costs 100 rollouts, so a searched game costs
about 20 decisions x 100 rollouts x half a game = 1,000 games, about 2 hours of one slot, or about 11 minutes on 12
slots. The hunt plays 5 x 300 = 1,500 searched games, so it is 1.5 million games, roughly 280 hours at 5,400 games an
hour. By replay it is twice that. Shrinking it to the budget of the other options (a few hours) leaves a few rollouts
per candidate, which is no search.

**Strength.** The highest in principle: it searches the real rules with no model. It is also the only option that can
find a line of play that depends on the exact state.

**Boundary impact.** A contract change in `simulation-control-api` (fork, or re-seed), implementations in
game-service, timeline-service and read-service, and a fidelity proof for restore. Gameplay stays authoritative, and
nothing is copied.

### (b) Shallow search

Look one round ahead, using an evaluation the services expose.

**Contract it needs.** A read-only evaluation in game-service: given the seat's entitled observation and a proposed
action, the expected change of the seat's score and standing at the end of the round, taken over the unknowns the seat
is entitled to be uncertain about (other seats' actions, unresolved chance). That is a new operation in a contract
(a new `simulation-control-api` operation or a participant-facing one) and an implementation that runs the real
resolution and scoring on a hypothetical. The other seats' simultaneous actions are not known at decision time, so the
evaluation must integrate over a model of them, which is a design decision of its own.

**Cost.** A decision evaluates every candidate (about 20 in an action round) once, or a few times for chance. At 6 ms an
acknowledgement-sized call, 20 decisions x 20 candidates x 6 ms is 2.4 s a game on top of 7 to 8 s, so about 1.3 times
the cost of a game. The hunt plays 5 x 300 searched games and 5 x 300 reference games: 5 x (300 x 1.3 + 300) = about
3,500 game-equivalents, **about 0.6 server hours** at 5,400 games an hour. Of that, 0.28 hours are the reference games,
which need no evaluation, 0.28 the play of the searched games and 0.09 their evaluation calls; only the calls scale with
the searched seat's decisions. The hours are no obstacle here; the horizon and the new contract are.

**Strength.** One round. It finds an exploit that pays within the round it is set up in and misses any line that pays over
eras, such as a Weaver chain, a bet placed for a later era or a paradox set up in advance, which is where an exploit would
most likely live. It is also a second statement of the scoring rules exposed as an API, which has to stay equal to what
resolution does.

**Boundary impact.** A new contract and a new operation in game-service. Gameplay stays authoritative because the
evaluation is the service's own resolution.

### (c) Strategy-level search (best response over a policy's parameters)

Keep the priority-bot structure for one seat. Make its choices parameters: the preference of each card type and special
action for that faction (every card, including Scan, Intercept and Obscure, which the standard bots rank below
everything), its lean toward the leading or trailing outcome, and its decision thresholds (when to play a counter, how
much stake before paradox cards, how many confirmed links before Tapestry). The search maximizes that seat's faction's
win ratio against the standard bots at the same table, using ordinary games.

**Size of the space.** Per faction, about 12 card-type affinities, 8 special-action affinities, 2 outcome-lean
parameters and about 6 thresholds: **about 28 parameters** per faction, plus the spread behaviors. A hunt does not
search all of them: each faction declares a **searched subset** of 12 to 16 parameters (its own specials, the
information cards Scan, Intercept, Decoy and Trace, Obscure, the shifters it supports its bet with and its own
thresholds), and every other parameter stays at its `faction-spread` default. Sixty-four candidates cover 16
dimensions much more densely than 28.

**Why a few hundred games are enough.** An exploit is a large advantage by definition (section 6): the searched seat wins
about 1.5 times as often as it normally does, which is about +0.5 of win ratio. The noise of a ratio was measured on the
last validation's tables (every faction at 3, 4 and 5 players, 600, 800 and 1,000 games): the standard error of a
faction's ratio, taken from its 95% interval, has a median of 0.064 to 0.070 and a 90th percentile of 0.077 to 0.092.
It grows as the square root of the games, so with 250 games it is about 0.13 (median) and 0.15 (90th percentile), and
with 300 games about 0.12. A difference of +0.5 is more than three standard errors, so a few hundred games show it. A
smaller advantage that 300 games would miss lies inside the balance band (0.8 to 1.25) and is balance noise, which the
normal validation already controls.

**Games and hours.** The hunt starts with the five-player tables, five cells (one per faction), and takes the three- and
four-player tables only if a signal appears or time is left. Per cell, with the successive-halving structure the
campaigns already use (`eta` 4, shared seeds that extend from round to round), the screening is: 64 candidates, the
baseline and a Latin hypercube sample of the faction's subset, on 32 games each; the best 16 on 128 games; the best 4 on
300 games, and a short list of those 4. That is 64 x 32 + 16 x 128 + 4 x 300 = 2,048 + 2,048 + 1,200 = **5,296 games per
cell and about 26,500 for the five cells, 4.2 hours at about 6,300 games an hour** (12 slots). A round extends the seeds
of the one before instead of replaying them, so the games actually played are fewer (4,440 at five players, see
"Exact game counts"); 5,296 is the figure the estimate and the budget use. The confirmation runs only in the cells whose
best candidate is flagged: 300 fresh games for the candidate and 300 for the standard seat on the same seeds, 600
games, about 0.1 hour each. Adding the three- and four-player tables repeats the screening twice, about 8.4 hours more.

**Exact game counts.** One seed plays one game per seat rotation of every faction set that holds the hunted faction,
so 5 games at five players (one set), 16 at four players (four sets) and 18 at three players (six sets). A round plays
the smallest whole number of seeds that gives at least its planned games, and the last round is capped at the games it
needs (300), not at the next power of `eta`. At five players that is 7, 26 and 60 seeds: 35, 130 and 300 games per
candidate. At four players 2, 8 and 19 seeds: 32, 128 and 304 games; at three players 2, 8 and 17: 36, 144 and 306.

**How the searched seat plays.** Every seat of a candidate's table plays that candidate's `faction-search` parameters,
and every threshold, spread behavior and preference of the parameters is keyed to a faction, so only the seat that plays
the hunted faction deviates from `faction-spread`; the other seats decide exactly as `faction-spread` does (pinned by a
test). This way every game of the cell has the hunted faction at a seat and none is wasted on a rotation where it sits
elsewhere. The same table of fixed parameter sets can also be played as an ordinary experiment, with no campaign.

At 32 games the noise of a ratio is about 0.37 (0.12 at 300 games, scaled by the square root of the games), so the first
round only discards: a candidate that is really +0.5 stays in the best quarter about 75% of the time (an estimate from
normal noise, to be checked on the first hunt). The decision is taken at 300 games, not at the screening. The report
states the realized noise and the share of candidates above the flag line.

**Can the existing campaign machinery drive it?** Part of it. A campaign today searches the rules parameter space of a
baseline and evaluates each candidate on the full matrix of all-bot tables against the balance criterion; policies take no
parameters (a manifest naming a parameter is refused as `INVALID_EXPERIMENT`). Reusable as is: the Latin hypercube
round 0, the shared seeds, the halving, the confirmation on fresh seeds derived from a separate master seed, the
durable worker, the decision log, and mixed tables (the criterion already has a table of one `random` seat against four
`faction-greedy` seats). Missing, all inside this repository: a parameterized policy bundle (an id, a version, a parameter
schema, a digest that covers the parameters), a policy parameter space the campaign can search in place of the rules
one, and an objective that is the searched seat's win ratio (and its gap to the standard seat on the same seeds) instead
of the gate and scored lines.

**Strength.** It finds anything a preference ordering and a few thresholds can express: a card the standard bots never
play but that wins for a faction, an action order, a bet that is too safe, a counter played too late. It does not find a
line that needs a condition on the exact state ("play X only after Y was played on this event"), and it is a local search
over 28 dimensions, so a narrow strategy surrounded by weak ones can be missed.

**Boundary impact.** None outside the workbench. Policies still see only the entitled observation, the services stay
authoritative for legality, resolution and scoring, and no contract or service changes.

### (d) An in-process forward model of the game, for search only

A copy of the rules inside the workbench (resolution, scoring, timers, chains, paradox, bands), used to play candidates
forward in microseconds.

**It conflicts with the boundary that gameplay stays authoritative in the real services and the workbench holds no
hand-written copy of the engine.** It is listed because it is the only option that makes move-level search affordable.
Estimated cost with a model: copying the state is free, so a rollout from a decision plays only what is left of the game,
half of it on average. At about 1 ms for a whole game, 20 decisions x 20 candidates x 50 rollouts x 0.5 ms is about 10 s
of one core a game, so the 1,500 searched games of the hunt are about 4 core hours; the model's speed is a guess until
one exists. This halving holds only here: option (a) has no state copy and pays a whole game per rollout when it replays
to the decision.

**What keeping it faithful costs.** Every rule or content change in game-service or timeline-service is also a change
to the model, and a model that is wrong gives a false answer in the safe direction: an exploit that exists in the real game
and not in the model is never found. The fidelity check that exists for the simulator (330 reproductions of a case matrix,
each by digest) would have to be extended to a check of model against services on every release, over states and not
only over bot-played games. Building the model is the size of the rules themselves. A found exploit would still have to be
re-played through the real services.

**What the rule owner would have to accept.** A second implementation of the rules in this repository, owned here, with the
maintenance and the release gating above, and an exception to the stated boundary. Nothing in this option is ruled out
for cost; it is not recommended because the boundary and the false-negative risk are the price of strength, and (c)
reaches the exploits a preference table can express without either.

### Comparison

| | Cost of one five-player hunt (estimate) | Strength | Contract / boundary | Information play |
|---|---|---|---|---|
| (a) Move-level through services | about 280 h | Highest in principle | Fork or re-seed in `simulation-control-api`, three services | Perfect knowledge unless re-seeded; hidden facts known by rollouts |
| (b) Shallow, round ahead | about 0.6 h | One round only | New evaluation operation in game-service | Priced only if it pays within the round |
| (c) Strategy-level | **about 3 h** | Anything a preference table expresses | None | Judged by real results |
| (d) In-process model | about 4 core h | High, bounded by model fidelity | Violates the no-engine-copy boundary | Needs belief sampling |

## 3. Information play

Information cards (Scan, Foresight, Expose, and the exact weights a seat earns) are only worth what the later decisions
do with them.

- **(a)** A rollout from the true state, even with other seats' hands hidden from the policy, starts from a state in which
  every fact is determined. A determinized search therefore sees no reason to look: Scan reveals what the rollout already
  knows, so it is valued at zero and the search never plays it. With the same-seed replay it is worse, because chance
  outcomes are known too. A correct search samples the hidden facts consistent with the observation for each rollout
  (information-set search), which needs the fork to accept sampled hidden state, a second new capability.
- **(b)** The evaluation takes an expectation over the seat's own uncertainty, so it can value a Scan whose information is
  used within the round, and undervalues one whose information pays in a later round, which is the common case. It
  undervalues information by the length of the horizon it lacks.
- **(c)** The standard bots and the searched seat play real games, in which hidden facts are really hidden, so there is no
  determinization at all. A Scan is worth what the games it was played in were won by. The weakness is the opposite
  one: the search cannot learn a use of information that needs a condition on what was learned beyond the thresholds it
  is given (for example "back the outcome the Scan showed only if its weight is above the band"). Giving the policy a
  Scan-conditioned bet rule as one of its parameters covers the common use.
- **(d)** A model can sample the hidden facts consistently with the seat's observation, so it can value information
  properly, at a multiple of the rollouts (one belief sample per rollout). Without sampling it has the same defect as (a).

## 4. What each option can and cannot find

The standard bots almost never play Scan, Intercept and Obscure. A faction with a winning line built on them is shown
by (c) as a searched seat whose confirmed ratio is far above its standard seat's, by (a) and (d) only if their search
reaches those cards at all (a move-level search tries them, which is its advantage), and by (b) only if they pay within the
round. None of the options detects a line that needs the cooperation of two seats, since the search moves one.

## 5. Risks of the recommended option

- **Narrow strategies.** 64 candidates in 12 to 16 dimensions is dense for a strategy that moves a few parameters, and
  still sparse for one that needs several moved together. A strategy that needs several parameters moved
  together can be missed. Mitigation inside the option: a second stage of coordinate search around the best candidate when
  time is left, and a hunt that finds nothing is reported as "no exploit within a preference-table strategy", not as "no
  exploit".
- **Winner's curse.** The best of 64 on shared seeds is optimistic; the confirmation on fresh seeds (300) removes it, as it
  does for rule search.
- **Noisy screening.** 32 games per candidate in the first round only discards; a real +0.5 survives it about 75% of the
  time. If the first hunt shows a lower rate, more games in round 0 are the first lever.
- **Strategy beyond the bots.** A searched seat may legitimately beat a bot by playing better. That is not an exploit; the
  threshold below separates the two.

## 6. Recommendation

**Option (c).** It costs about three server hours for the five-player tables (option (b) is cheaper), touches no
contract or service, keeps the policies entitled-observation only, measures information by results, and is not limited
to a one-round horizon, which is why it is preferred to (b).

**What counts as an exploit (a proposal for the rule owner).** The balance criterion reads a faction's win ratio (its win
rate over its fair share, `1/N`): scored band 0.8 to 1.25, gate band 0.5 to 2.0. An exploit is a large advantage, not a
subtle one: the searched seat wins **at least 1.5 times as often as the faction normally does**. The normal ratio is 1.0
or the standard seat's own ratio on the same seeds, whichever is higher, so for a normal ratio of 1.0 an exploit is a
ratio of 1.5, about +0.5. The decision is taken on 300 fresh games for the candidate and 300 for the standard seat, and a
cell is **flagged when the candidate's ratio is at least 1.25 times the normal ratio**, midway between normal and exploit.
With a standard error of about 0.12 at 300 games (section 2), a real exploit of 1.5 is flagged about 98% of the time and a
faction at its normal 1.0 about 2% of the time (normal noise, estimates). A flag is reported with its ratio, the standard
seat's ratio and the parameters found, for the rule owner to judge; a ratio between 1.0 and 1.25 times normal is balance
noise and is not reported as an exploit.

**Server hours for one hunt.** The five-player tables first: **about 4.2 hours at 12 slots**, from 26,480 screening
games and 600 confirmation games for each flagged cell, at the rates of section 1 (about 6,300 games an hour at 12
slots in the last finish gate). The three- and four-player tables add about 8.4 hours and are taken only on a signal or
with time left. It runs on the rented server, per the project's rule for campaigns, with the rules that are then in force.

**If it did not fit.** A larger server is the other lever: the simulator's games per hour grew from 1,424 at one slot to
6,309 at eight (55% to 78% of linear per doubling), so a machine with several times the processors would finish sooner,
but not in proportion. The report does not count on it: the 12-slot figure was measured under heavier machine load and
nothing was measured beyond it.

## 7. What was built

[simulation-workbench#206](https://github.com/temporal-rift/simulation-workbench/issues/206) implements option (c):

- **`faction-search` 1.0.0** accepts integer parameters declared by a schema (preferences per card type and special
  action, the leading or trailing lean, the thresholds and the spread behaviors), each on a declared grid. With its
  defaults it decides as `faction-spread` does, decision for decision and in the use of its entropy, covered by a pinning
  test. Its artifact digest covers the parameters; a manifest names several parameter sets of the bundle as several
  policies, told apart by digest, and the reports tell the seats apart by it.
- **A policy parameter space** (`/api/v1/policy-parameter-spaces`) declares each parameter's range and step and each
  faction's searched subset of 12 to 16 parameters.
- **A campaign of kind `POLICY_HUNT`** names one faction and one player count and follows the plan of section 2 (c):
  64 candidates, the existing halving search, the faction's win ratio as objective, a screening gate and a
  confirmation on 300 fresh seeds of the candidate and of the standard seat.
- **The report** states, per cell, the confirmed ratio and its interval, the standard seat's ratio, whether the cell is
  flagged (at least 1.25 times the normal ratio) or an exploit (at least 1.5 times), the parameters found, the realized
  screening noise and the share of candidates above the flag line.

**The screening gate.** A flag is defined on the confirmed ratio, which needs the confirmation. The search therefore
decides whether to confirm from the best candidate's win ratio over its 300 games of the last round: at least 1.25, the
normal ratio taken as 1.0 because no standard seat is played in the screening. The ratio of the best of 64 on shared
seeds is optimistic, so the gate is permissive by design; a cell that passes it costs about 0.1 hour and the
confirmation on fresh seeds decides. A cell that does not pass ends without a confirmation and its report says so.

**Results characterize bot strategies only.** Everything a hunt finds is about the strategies a preference table
can express, played by bots against bots. It says nothing about balance among human players, and a cell without a flag
is evidence, not a guarantee, that no exploit exists.

## 8. What is not established

- **A fresh split of a game's time into policy, services and messaging on the simulator.** The split exists only for the
  light lane; the simulator's policy share is inferred to be at most the lane's 0.1% of a longer game, and was not
  measured. The 100 commands a game is the lane's count of acknowledgements.
- **Whether a replay re-seeds.** Section 2 assumes, from the contract (the seed is part of the opened context), that chance
  outcomes are fixed by it; whether any service derives chance from something the contract does not show was not read in the
  services.
- **Every cost in sections 2 and 6 beyond the published rates is an estimate** built from the 100 commands and the 5,400
  games an hour; (d)'s rollout speed is a guess until a model exists.
- **The parameter count and the search sizes** (a 12 to 16 parameter subset of about 28 per faction, 64 candidates, `eta`
  4, 32/128/300 games, 300 confirmation games) are a starting design. The noise they rest on comes from tables of all-standard seats; a mixed table of one
  searched seat against standard seats was not measured, so the first hunt should report its realized noise.
- **The 75% survival of a real exploit in the first round and the 98% and 2% flag rates** are normal-noise estimates, not
  measurements.
