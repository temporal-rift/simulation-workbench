# Spike: how to build a search bot that hunts exploits

Answers [simulation-workbench#198](https://github.com/temporal-rift/simulation-workbench/issues/198), under
[game-service#381](https://github.com/temporal-rift/game-service/issues/381).

**Verdict: build option (c), a strategy-level best response, and keep (a) to (d) as the record of what was weighed.**
One seat keeps the priority-bot structure but its preference table and decision thresholds, including the cards the
standard bots ignore, become parameters searched with ordinary games against the standard bots. It needs no change to
any service or contract, it costs about 14 server hours for one hunt (five factions by three table sizes), and it is
the only option whose information play is judged by real outcomes. Its limit is that it finds strategies a preference
table can express, not conditional combinations. The rule owner decides; the draft implementation issue is in section 7.

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

Costs are *estimates* unless stated. They use about 25 decisions of the searched seat per game (100 commands over a
four-player table's seats), and a rollout or a game costs all of its seats' commands. Using the game-wide 100 as the
searched seat's decisions would be a conservative upper bound, four times higher for (a), (b) and (d).

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
about 25 decisions x 100 rollouts x half a game = 1,250 games, about 2.6 hours of one slot, or about 14 minutes on 12
slots. A hunt needs about 1,000 searched games per cell for a usable ratio (section 6), so the hunt is about
15 cells x 1,024 x 1,250 = 19 million games, roughly 3,600 hours at 5,400 games an hour. By replay it is twice that.
Shrinking it to the budget of the other options (about 15 hours) leaves about one rollout per candidate, which is no
search.

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
acknowledgement-sized call, 25 decisions x 20 candidates x 6 ms is 3 s a game on top of 7 to 8 s, so about 1.4 times
the cost of a game. A hunt plays 1,024 searched games and 1,024 reference games per cell: 15 x (1,024 x 1.4 + 1,024) =
about 37,000 game-equivalents, **about 7 server hours** at 5,400 games an hour. Only the evaluation calls scale with
the searched seat's decisions; the rest is plain games. The 6.8 hours split as 2.8 for the 15,360 reference games, which
need no evaluation, 2.8 for the play of the 15,360 searched games, and 1.1 for their evaluation calls (4.6 hours had the
calls been counted for all seats). That is why the estimate fell from 10 to 7 hours and not to 2.5. A cheaper in-process
call would bring it near a game's cost. The hours are no obstacle here; the horizon and the new contract are.

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
parameters and about 6 thresholds: **about 28 parameters**, searched per faction and table size.

**Games and hours.** Per cell (one faction at one table size), with the successive-halving structure the campaigns
already use: 64 candidates from a Latin hypercube sample, `eta` 4, screening on 16, 64 and 256 seeds, so about
1,024 games in each of the 3 rounds = **3,072 search games**. The best candidate is then confirmed on 1,024 fresh seeds,
with the standard seat played on the same 1,024 seeds as its reference = **2,048 confirmation games**. That is **5,120
games per cell**, **76,800 for one hunt** of 15 cells, which is **14.2 hours at 5,400 games an hour** (12 slots) or about
19 hours at the default 4 slots (4,003 an hour). The cost scales with the number of cells and linearly with the
seeds; the confirmation alone is 30,720 games, 5.7 hours.

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
half of it on average. At about 1 ms for a whole game, 25 decisions x 20 candidates x 50 rollouts x 0.5 ms is about 12 s
of one core a game, so the 15,360 searched games of a hunt (15 x 1,024) are about 55 core hours, a few hours on 16
cores; the model's speed is a guess until one exists. This halving holds only here: option (a) has no state copy and
pays a whole game per rollout when it replays to the decision.

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

| | Cost of one hunt (estimate) | Strength | Contract / boundary | Information play |
|---|---|---|---|---|
| (a) Move-level through services | about 3,600 h | Highest in principle | Fork or re-seed in `simulation-control-api`, three services | Perfect knowledge unless re-seeded; hidden facts known by rollouts |
| (b) Shallow, round ahead | about 7 h | One round only | New evaluation operation in game-service | Priced only if it pays within the round |
| (c) Strategy-level | **about 14 h** | Anything a preference table expresses | None | Judged by real results |
| (d) In-process model | about 55 core h | High, bounded by model fidelity | Violates the no-engine-copy boundary | Needs belief sampling |

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

- **Narrow strategies.** 64 candidates in 28 dimensions is sparse. A strategy that needs several parameters moved
  together can be missed. Mitigation inside the option: a second stage of coordinate search around the best candidate, and
  a hunt that finds nothing is reported as "no exploit within a preference-table strategy", not as "no exploit".
- **Winner's curse.** The best of 64 on shared seeds is optimistic; the confirmation on fresh seeds (1,024) removes it, as it
  does for rule search.
- **Strategy beyond the bots.** A searched seat may legitimately beat a bot by playing better. That is not an exploit; the
  threshold below separates the two.

## 6. Recommendation

**Option (c).** It costs about a working day of server time (option (b) is cheaper), touches no contract or
service, keeps the policies entitled-observation only, measures information by results, and is not limited to a one-round
horizon, which is why it is preferred to (b).

**Exploit-hunt threshold (a proposal for the rule owner).** The balance criterion reads a faction's win ratio (its win
rate over its fair share, `1/N`): scored band 0.8 to 1.25, gate band 0.5 to 2.0. The standard bots' own ratios under the
current rules sit around 0.4 to 1.5 at 1,000 games per size, with 95% intervals of about plus or minus 0.1 to 0.2. A
confirmed searched seat counts as an exploit when **both** hold, on the 1,024 fresh confirmation seeds:

1. its win ratio's lower 95% bound is **above 1.5**, the middle of the way from the scored upper edge (1.25) to the gate
   upper edge (2.0), and it is a definite exploit above 2.0; and
2. its ratio exceeds the standard seat's ratio on the same seeds by **at least 0.3** (about 3 standard errors of the
   difference at 1,024 games, whose ratio error is about 0.05 to 0.07 at a four-player table).

The first condition says the faction wins too often; the second says the cause is the strategy and not a faction that is
already above band for the standard bots. A ratio between 1.25 and 1.5 is reported as a finding and not as an exploit.
The numbers are a proposal; the intervals follow from `sqrt(p(1-p)/n)` with `p` near `1/N` and should be recomputed from
the first hunt.

**Server hours for one hunt: about 14 (14.2 at 12 slots, about 19 at 4).** 5 factions x 3 table sizes x 5,120 games. At the
rate of the confirmation matrix this is about 4 times the 3.2-hour confirmation of one candidate over 17,160 games. It
runs on the rented server, per the project's rule for campaigns.

## 7. Draft of the implementation issue

> **Title:** Add a parameterized policy bundle and a policy search that hunts a faction's best response
>
> **Summary.** Balance by bots only finds what the standard bots play. Add a policy whose preferences and thresholds are
> parameters, and a campaign that searches them for one seat, so a faction that wins too often when played well shows up
> before release.
>
> **Expected behavior.**
> 1. A bundle `faction-search` accepts parameters declared by a schema: per card type and per special action a preference
>    (including the cards the standard bots rank last), the leading/trailing outcome lean, and the thresholds the standard
>    bots hold as constants. With the default parameters it decides as `faction-greedy` does, which is covered by a pinning
>    test. The artifact digest covers the parameters.
> 2. A policy parameter space, served like the rules parameter spaces, declares the ranges and steps.
> 3. A campaign over one faction and one table size runs the existing halving search (Latin hypercube, shared seeds, fresh
>    confirmation seeds) with the searched seat's win ratio as its objective, on mixed tables of the searched seat against
>    the N-1 standard seats of an N-player cell (two, three or four), and confirms the best candidate with the standard seat as reference on the same seeds.
> 4. The report states the confirmed ratio and interval, the gap to the standard seat, whether it meets the exploit
>    threshold, and the parameters found, per faction and table size.
>
> **Contract and data changes.** None outside this repository: a policy parameter schema in the policy catalog and a new
> campaign kind. No change to the services or `apis`.
>
> **Acceptance criteria.** Default parameters reproduce `faction-greedy` decision for decision; a hunt of 15 cells runs
> through the campaign worker on the simulator and ends with a report; the report separates an exploit from a better
> play by the two conditions above; the run is repeatable from its master seeds; documentation states that results
> characterize bot strategies only.
>
> **Cross-repo dependencies.** None. Part of game-service#381.

The implementation issue is created only after the rule owner decides on an option.

## 8. What is not established

- **A fresh split of a game's time into policy, services and messaging on the simulator.** The split exists only for the
  light lane; the simulator's policy share is inferred to be at most the lane's 0.1% of a longer game, and was not
  measured. The 100 commands a game is the lane's count of acknowledgements.
- **Whether a replay re-seeds.** Section 2 assumes, from the contract (the seed is part of the opened context), that chance
  outcomes are fixed by it; whether any service derives chance from something the contract does not show was not read in the
  services.
- **Every cost in sections 2 and 6 beyond the published rates is an estimate** built from the 100 commands and the 5,400
  games an hour; (d)'s rollout speed is a guess until a model exists.
- **The parameter count and the search sizes** (28 parameters, 64 candidates, `eta` 4, 16/64/256 seeds, 1,024 confirmation
  seeds) are a starting design; the first hunt should report its realized noise so they can be resized.
