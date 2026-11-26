# Scoring models and re-scoring

Two synchronous operations, neither of which stores anything, judge a scoring design before a service implements it.
Both price **score reasons** the way the game does: a points table per reason, the same shape as the served
`game.rules.scoring.score-deltas`, plus escalations for reasons whose points grow within a run. A value evaluated here
maps one to one to the served configuration. They require `simulation:read`.

| Operation | What it does |
|---|---|
| `POST /api/v1/scoring-models/evaluations` | Plays the scoring layer alone from per-era rates and sweeps the sensitivity of the win ratios |
| `POST /api/v1/runs/{runId}/rescorings` | Re-scores the score reasons recorded in a run's cases under an engine |

## The engine

A `ScoringEngine` holds:

- `maxEras` (1-10): the race ends after this era; the highest total wins and ties share.
- `reasonPoints` (1-64 items, unique `scoreReason`): flat points, -50 to 50, per occurrence of a reason.
- `escalations` (0-8 items, unique `name`): reasons whose points grow within a run. An escalation has `scoreReasons`
  (each occurrence of any of them extends the run), `base` (the points of the first occurrence of a run), `increment`
  (-20 to 20, added for each further occurrence) and `resetOn` (`MISSED`, `VOIDED` or `NOT_PLACED`: the seat-era bet
  outcomes that end the run).

There is no win threshold and there are no objectives. A seat is scored era by era:

1. Every occurrence of a score reason in the seat-era is scored one by one; a joined record counts each reason
   separately and a repeated reason counts each time.
2. An occurrence of an escalated reason extends that escalation's run to *n* and scores `base + increment × (n − 1)`.
   Any other occurrence scores its `reasonPoints`.
3. After the era's reasons are scored, every escalation whose `resetOn` holds the seat-era's bet outcome resets its run
   to 0.
4. Banked points never change.

Points are kept in exact millionths of a point, so equal totals stay equal however they were reached.

A reason may be priced flat or escalated, never both, and no reason may belong to two escalations. A reason repeated in
`reasonPoints`, a reason priced both ways, a reason in two escalations or a repeated escalation `name` is refused with
`400 INVALID_REQUEST` and a `field` naming the value.

### The seat-era bet outcome

The bet outcome is used only by `resetOn`. A seat-era is `WON` when it lists a win reason of its faction and `MISSED`
when it lists only a miss reason. It is `VOIDED` when the bet was placed but recorded neither, and `NOT_PLACED` when no
bet was placed (a bet reason listed in such an era settles nothing). A bet is placed by the plays that the bet metrics
count as placing it (see [Bet survival](evidence-and-reports.md#bet-survival-analysis-version-4)).

| Faction | Win reasons | Miss reasons |
|---|---|---|
| Erasers | `ANNIHILATED_OUTCOME` | `ANNIHILATION_WASTED` |
| Prophets | `EVENT_RESOLVED_AS_WRITTEN`, `FULFILLMENT_SUCCEEDED` | `EVENT_RESOLVED_DIFFERENTLY_THAN_WRITTEN` |
| Revisionists | `SECRET_OUTCOME_WON` | `SECRET_OUTCOME_MISSED` |
| Weavers | `CHAIN_LINK_ADDED` | `CHAIN_LINK_MISSED` |
| Activists | `DECLARED_OUTCOME_WON`, `DECLARED_OUTCOME_WON_WITH_RALLY` | `DECLARED_OUTCOME_MISSED`, `DECLARED_OUTCOME_MISSED_WITH_RALLY` |

### A worked example: the approved design as an engine

The approved design pays the Prophet `FULFILLMENT_SUCCEEDED` 14 against `EVENT_RESOLVED_AS_WRITTEN` 7, charges an
Activist miss −4 in Rally mode and −2 in Momentum mode, grows the Weaver's links (base 6, increment 3, reset only by a
voided bet) and the Activist's streak (base 6, increment 2, reset by a miss or an era without a bet), and retires the
chain completion and break. As an engine, with every value of the design:

```json
{
  "maxEras": 5,
  "reasonPoints": [
    { "scoreReason": "ANNIHILATED_OUTCOME", "points": 6 },
    { "scoreReason": "ANNIHILATION_WASTED", "points": -1 },
    { "scoreReason": "EVENT_RESOLVED_AS_WRITTEN", "points": 7 },
    { "scoreReason": "FULFILLMENT_SUCCEEDED", "points": 14 },
    { "scoreReason": "EVENT_RESOLVED_DIFFERENTLY_THAN_WRITTEN", "points": -4 },
    { "scoreReason": "SECRET_OUTCOME_WON", "points": 6 },
    { "scoreReason": "SECRET_OUTCOME_MISSED", "points": -2 },
    { "scoreReason": "CHAIN_LINK_MISSED", "points": 0 },
    { "scoreReason": "CHAIN_COMPLETED", "points": 0 },
    { "scoreReason": "CHAIN_BROKEN", "points": 0 },
    { "scoreReason": "DECLARED_OUTCOME_MISSED_WITH_RALLY", "points": -4 },
    { "scoreReason": "DECLARED_OUTCOME_MISSED", "points": -2 },
    { "scoreReason": "CORRUPTED_OPPONENT_CARD", "points": 2 },
    { "scoreReason": "MIMIC_CONTRIBUTED_TO_WIN", "points": 1 },
    { "scoreReason": "MIMIC_NEVER_TRACED", "points": 4 },
    { "scoreReason": "EXPOSE_SIGNATURE_REVEALED", "points": 2 }
  ],
  "escalations": [
    { "name": "weaver-link", "scoreReasons": ["CHAIN_LINK_ADDED"], "base": 6, "increment": 3,
      "resetOn": ["VOIDED"] },
    { "name": "activist-streak",
      "scoreReasons": ["DECLARED_OUTCOME_WON", "DECLARED_OUTCOME_WON_WITH_RALLY"], "base": 6, "increment": 2,
      "resetOn": ["MISSED", "VOIDED", "NOT_PLACED"] }
  ]
}
```

The paradox and other reasons the design leaves at today's values are listed in `reasonPoints` in the same way, at
the values the run's variant configures: a re-score needs every reason the variant configures, so a complete request
lists them all.

A Weaver that records `CHAIN_LINK_ADDED` in eras 1, 2 and 4, `CHAIN_LINK_MISSED` in era 3 and a voided bet in era 5
scores 6, 9, 0, 12 and 0, and its run is 0 after era 5. An Activist that wins in eras 1 and 2, misses in Rally mode in
era 3 and wins in era 4 scores 6, 8, −4 and 6. A seat-era that records `CHAIN_LINK_ADDED,CHAIN_COMPLETED` adds the
link's points and nothing for the completion, whatever the run's variant paid for it.

## Evaluating a model

`POST /api/v1/scoring-models/evaluations` plays `gamesPerPlayerCount` games (1 000-200 000, default 20 000) of the
scoring layer alone for each requested player count. It is an analysis model, not a game engine: it never plays cards or
resolves events.

The request holds the `engine`, the `factions` at the table (at three and four players each game draws its set
uniformly from the combinations of them, at five players all five must be named, and a player count above the number of
factions is refused with `field` `playerCounts`), the `playerCounts`, a `seed`, an optional `sweep` (at most 64 items)
and exactly one `SeatEraRates` item per requested player count and faction (`field` `rates` for a missing, surplus or
repeated one).

### Reading the inputs from a report

A `SeatEraRates` item describes one seat-era of one faction at one player count:

| Field | Meaning | Read it from |
|---|---|---|
| `betReasons` | Chance that the seat-era's bet settles with each of the faction's own bet reasons | `reason_occurrences_per_era` of that faction and reason, in the cohort of the player count |
| `voidedRate` | Chance that a placed bet records no bet reason | `bet_voided_rate_per_era` of that faction |
| `otherReasons` | Mean occurrences per seat-era of every other reason, including reasons configured at 0 points | `reason_occurrences_per_era` of that faction and reason |

The rest, `1 − Σ betReasons − voidedRate`, is `NOT_PLACED`. Both metrics belong to
[analysis version 6](evidence-and-reports.md#reason-occurrences-and-voided-bets-analysis-version-6), so a report of a
recording run gives a model request directly. The rates of the model are measured with the priority bots; the same
run is then re-scored (below) to confirm the model on real games.

### Playing a modelled game

Each seat-era draws exactly one of: one of the seat's bet reasons, `VOIDED`, or `NOT_PLACED`, in the order the
`betReasons` are listed. A drawn bet reason is scored by the engine, and its class in the table above gives the outcome
used for resets. Each `otherReasons` item then adds `perEra × points` of its reason as an expected value, so an
`otherReasons` reason must be priced flat: a reason that belongs to an escalation, or that is a bet reason of any
faction, is refused with `field` `rates[i].otherReasons[j].scoreReason`. A bet reason that is not a bet reason of the
item's faction, a reason in `rates` that the engine does not price, and `betReasons` rates plus `voidedRate` above 1
(`field` `rates[i]`) are refused too. Every game lasts `maxEras`.

The stream of a game derives from the seed, the player count and the game index only, and the results are summed as
exact integers, so equal requests give byte-identical responses on any number of threads. A shared win counts 1/*k* to
each of its *k* winners. A cell's `ratio` is the faction's wins over the games it was seated in, times the player
count, so 1 is an even share; `lower` and `upper` are its 95% interval, from the sample variance of each game's win
share.

### Sweeps

Each sweep item moves one value by `+step` and by `−step` over the same games as the base evaluation, so the reported
`ratioChange` of every cell is the effect of the value, not noise. `parameter` is one of:

- `maxEras`;
- `reasonPoints.<REASON>`;
- `escalations.<name>.base` and `escalations.<name>.increment`;
- `rates.<playerCount>.<FACTION>.betReasons.<REASON>`;
- `rates.<playerCount>.<FACTION>.voidedRate`;
- `rates.<playerCount>.<FACTION>.otherReasons.<REASON>`.

Moving a `betReasons` rate or `voidedRate` takes the difference from `NOT_PLACED`, or gives it back to it. A sweep is
refused with `field` `sweep[i].parameter` when its path names something the request does not contain, and with
`sweep[i].step` when the step is zero, is not an integer for an integer value, moves the value out of its range, or
makes the rates sum above 1 or a rate fall below 0.

### One bet reason per seat-era

The model assumes at most one bet reason per seat-era. Under the default rules every faction meets it: a Weaver adds at
most one chain link per era, Annihilate and Rewrite are once-per-era specials, an Activist receives at most one
declaration score per era, and a Prophet writes at most `game.rules.foresight.writings-per-era` outcomes per era
(default 1). A writing counts only in the era it was written in, so an event that cascades into a later era does not
bring its writing along. The assumption fails only if those settings are raised, for example `writings-per-era` above 1
for Prophets, and the rules give no other way to record two bet reasons in one era. Then a faction's
`reason_occurrences_per_era` for its bet reasons sum to more than its placement rate (`bets_scored` per seat-game
divided by the eras played), and the difference is the mean excess of occurrences per seat-era over one bet reason,
which the model does not play (to read a share of seat-eras with more than one, count those seat-eras directly). The difference also absorbs a bet reason listed in an era where no bet was placed, which
settles nothing in the re-score but still occurs, so read a non-zero difference as an upper bound of the excess. Check
it on a recording run before reading a model result for such a faction. This is read from the rules and the game's
scoring code; it is not measured on a run.

## Re-scoring a run

`POST /api/v1/runs/{runId}/rescorings` re-scores the succeeded cases of one `variantLabel` and `tableId` of a run under
an `engine`, storing nothing.

- Every occurrence of a score reason recorded in a seat-era is scored by the engine, one by one. The recorded points are
  not used at all, so there is no "other" score kept at its recorded value.
- The seat-era bet outcome is derived as the bet metrics derive it and only drives resets.
- The race is judged after `maxEras`. A case whose game ended earlier by a timeline collapse or at its final era is
  judged where it ended; any other earlier end is `TRUNCATED`, enters no ratio and is counted in `truncatedCases`.
  Judge an engine on a recording variant (see [the recording variant](experiments.md#the-recording-variant)), where
  normal victory never ends a game early. A run with unfinished cases is re-scored over its succeeded cases and returns
  `complete: false`. A cell's interval is a block bootstrap over seeds.
- Before any case is read, the engine must price exactly what the run's variant configures: a configured reason the
  engine leaves unpriced is refused with `field` `engine`, the message naming every such reason, and a priced reason
  the variant does not configure is refused with `field` `engine.reasonPoints[i].scoreReason` or
  `engine.escalations[i].scoreReasons[j]`. A recorded reason that the variant does not configure fails the request the
  same way and is never counted as 0.
- An unknown run, or a `variantLabel` or `tableId` the run does not contain, is `404 RESOURCE_NOT_FOUND`.

A design that retires a score is re-scored by pricing it at 0 in `reasonPoints`: the reason is still recorded, so it is
still listed, and it adds nothing.
