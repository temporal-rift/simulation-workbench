# Balance criterion version 3

Status: proposed. It becomes approved when the design owner approves the pull request that adds it; any amendment is
made in that review or in a later version, never by editing an approved version. Versions 1 and 2 stay served and
unchanged.

Version 3 is version 2 measured with a different primary bot. Every decision D-01 to D-13 of version 2 holds as
written, with `faction-greedy-v2` read wherever version 2 says `faction-greedy-v1`. Nothing else changes: the lines,
bands, roles, weights, interval rules, populations, evidence rule and the 104 confirmation seeds are version 2's.

## Changes from version 2

**The primary bot is `faction-greedy-v2`.** D-10 chose the greedy bot as the primary population because it
"approximates players pursuing their faction's goals". The baseline of parameter space 3 showed that `faction-greedy-v1`
does not:

1. It sees only the printed weights of the event cards (and its own Scan, which almost no faction plays), so every
   seat aims at the same printed leader of the era. The Eraser erases exactly that outcome, so the bets of the other
   factions fail together every era.
2. Its fixed affinities rank the factions' counters (Tapestry, Reweave, Seal, Fulfillment, Expose, Mimic, Corrupt)
   below ordinary cards, so they are almost never played, and it wastes actions that have no effect: a Prophet writes
   the same event three times an era, a Weaver threads again while its link is pending.
3. The data shows the collisions. In the baseline (12 seeds), paradox findings per game are 1.27, 2.46 and 4.22 at 3, 4
   and 5 players. Divided by the share of games that seat Eraser and Weaver together (3 of 10 faction sets, 3 of 5,
   and 1 of 1), each comes to about 4.2: about one Chain Conflict per era in those games and almost none elsewhere.
   The Eraser's ratio swings from about 2.2 without a Prophet to 0.5 to 1.1 with one, because the Prophet always
   writes what the Eraser always erases.

Win ratios measured with that bot describe its collisions rather than the factions, and a rules search cannot correct
them. `faction-greedy-v2` reads what a player sees (public bands, the Activist's declaration, the round summary, its
own actions and the Weaver's public chain) and applies one recipe to every faction: skip actions it can tell are
wasted, back its own commitment of the era, do not feed another player's declaration, and play its faction's counter
when the counter's trigger is on the table. It has no rule aimed at a faction, so it cannot lower one by construction.
The README's "Bot policies" section lists its rules.

## D-13 Tables

| Table id | Seats 0 to 4 | Phases |
|---|---|---|
| `faction-greedy-v2` | `faction-greedy-v2` on every seat | `SCREENING`, `CONFIRMATION` |
| `random-v1` | `random-v1` on every seat | `SCREENING`, `CONFIRMATION` |
| `random-seat-vs-faction-greedy-v2` | seat 0 `random-v1`, seats 1 to 4 `faction-greedy-v2` | `CONFIRMATION` |

G-07 reads the random seat of `random-seat-vs-faction-greedy-v2`.

## The bot is frozen for measurement

`faction-greedy-v2` changes only for a defect that a run shows: a wasted action, an illegal choice, or a counter that
never fires when its trigger is present. Each change is a new bundle version and, if a criterion should use it, a new
criterion version. It is never changed to move a win ratio; a faction that v2 makes strong or weak is a finding about
the rules.

## Open questions

The within-seed correlation behind the 104 seeds (D-09) was measured for `faction-greedy-v1`. It should be measured
again for `faction-greedy-v2` from the first five-player run; if it is materially higher, the sample is revisited in a
later version.
