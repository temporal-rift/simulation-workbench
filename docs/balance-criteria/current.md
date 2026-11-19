# Balance criterion version 4

Status: proposed. It becomes approved when the design owner approves the pull request that adds it; any amendment is
made in that review or in a later version, never by editing an approved version. Versions 1 to 3 stay served and
unchanged.

Version 4 is version 3 measured with a different primary bot. Every decision of version 3 holds as written, with
`faction-greedy-v3` read wherever version 3 says `faction-greedy-v2`. Nothing else changes: the lines, bands, roles,
weights, interval rules, populations, evidence rule and the 104 confirmation seeds are version 3's.

## Changes from version 3

**The primary bot is `faction-greedy-v3`.** Version 3 froze `faction-greedy-v2` except for defects a run shows. The
run on parameter space 4 showed two:

1. **Paradox choices by habit.** In paradox resolution v2 ranks Stabilize and Detonate by fixed faction affinity: the
   Prophet and the Weaver always stabilize, the Eraser always detonates. With one Stabilize enough to clear, almost no
   paradox cascaded in any table holding a Prophet or a Weaver, whatever clearing or cascading cost. Line L-07
   (cascade rate) therefore measured the affinity table, not the rules, and a rule that makes Detonate contest
   Stabilize would cascade nearly every five-player paradox for no rule reason.
2. **The retired Tapestry rule.** Under the current rule an Annihilate on a protected pending link keeps the link
   pending. v2 assumed the link was cleared: it tried a second Thread, which the service refuses, and never Reweaved
   while Tapestry was armed, so the Weaver never used the escape the rule gives it.

`faction-greedy-v3` is v2 with those two fixes and nothing else:

- In paradox resolution it plays Stabilize on an affected event only when clearing it is worth more to the seat than
  cascading it, judged by the seat's own commitment on that event (worth about one signature win) and the table's
  Stabilize, Detonate and cascade score values. It plays Detonate only when cascading is worth more than nothing, which
  happens when Detonate does not contest Stabilize and the other players pay double. Otherwise it plays as v2 among the
  remaining cards, never Stabilize or Detonate by habit.

- While Tapestry does not absorb the Annihilate, a second Thread is always wasted and Reweave stays allowed with
  Tapestry armed.

While the contest is on, as the configuration server serves it, the Detonate branch cannot fire: cascading always costs
the detonator, so the bot never plays Detonate and no line sees that branch. Parameter space 4 does not declare the
contest or the Detonate cost, so nothing searches them; parameter space 5 declares both, and a campaign on it measures
the branch. Until then the uncontested branch is defensive and unmeasured.

The table's score values come from the case's announced rules, which every player knows; the observation carries them
as table rules. The projection a player reads does not say how many events have cascaded, so the bot does not weigh
an imminent collapse; that is a known limit, not a rule aimed at a faction.

## Tables

| Table id | Seats 0 to 4 | Phases |
|---|---|---|
| `faction-greedy-v3` | `faction-greedy-v3` on every seat | `SCREENING`, `CONFIRMATION` |
| `random-v1` | `random-v1` on every seat | `SCREENING`, `CONFIRMATION` |
| `random-seat-vs-faction-greedy-v3` | seat 0 `random-v1`, seats 1 to 4 `faction-greedy-v3` | `CONFIRMATION` |

G-07 reads the random seat of `random-seat-vs-faction-greedy-v3`.

## The bot is frozen for measurement

`faction-greedy-v3` changes only for a defect that a run shows, as a new bundle version and, if a criterion should use
it, a new criterion version. It is never changed to move a win ratio.
