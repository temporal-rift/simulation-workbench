# Bot policies

## Bot policies

Four baseline policies play every normal decision window. Each decision uses one frozen
`EntitledObservation` per seat: the seat's own faction and hand (in action rounds every card with its
playability, and the playable ones as candidates), the visible events (printed weights, the current era's
public band once published, plus an exact weight only when the seat earned it, for example through Scan), the
other participants' identifiers, the board, and the open window. The board holds the current era's public
Activist declarations, the latest public round summary, the seat's own accepted submissions of the era, the
game's public Weaver chain, and the open paradoxes; every board fact is settled before the window opens, and
scores, timers and revisions are never read. The observation also carries the table's rules, the paradox rules every
player knows before the game starts (whether Detonate contests Stabilize, what Stabilize, Detonate and a cascade
cost, and whether Tapestry absorbs a chain conflict), read from the case's effective rules. Observer evidence, opposing hands or credentials, and
execution-control state have no field on the type, so they cannot influence a choice. An observation without
board facts keeps the canonical text it had before the board existed, so retained evidence still compares equal; an
observation without table rules reads as the served defaults and compares equal to a reproduction that attaches them.

| Bundle | Id / version | Behavior |
|---|---|---|
| `random` | `random` / `1.0.0` | Uniform seeded draw over the canonically ordered candidates, pass or decline included |
| `faction-greedy` | `faction-greedy` / `1.0.0` | One recipe for every faction: reads the board, skips wasted actions and specials the table reports unavailable, backs its own commitment, does not feed another player's declaration, plays its faction's counter on its trigger, and chooses paradox cards by its stake and the table's costs |

A manifest references a bundle by `id`, `version`, and `artifactDigest`. The digest is the SHA-256 of the
bundle identity and its canonical definition (for `faction-greedy`, its rules, bonuses and the preference table), so a
changed preference needs a new bundle version. The baselines accept no parameters. An unknown id/version
or any parameter yields `INVALID_EXPERIMENT`; a wrong digest yields `MANIFEST_MISMATCH`.

Windows covered: seven-to-five hand selection, declaration or decline, action rounds (every card and
special target shape, or pass), paradox resolution (card or pass; an empty offer passes), and terminal
readiness. Candidates are enumerated in one canonical order from published submission shapes and entitled
state only; the services stay authoritative for legality, resolution, and scoring.

**Entropy.** Each stream is derived from the policy seed, seat, window key, and rejection count under its own
domain-separation label. Service entropy is never an input, so equal observation, version, and seed always give
the same choice.

**`faction-greedy` preferences.** Each faction has an affinity per card type and special action, and a lean
toward the highest or lowest known outcome weight (suppress-style actions by Erasers and raise-style actions by
the others aim at the leading outcome). Activists prefer declaring over declining; reactive paradox cards rank
by faction (for example Stabilize for Prophets and Weavers, Detonate for Erasers). Players are targeted by seeded
tie-break only, since opponents' factions and hands are hidden. Because it sees only printed weights, every seat aims
at the same printed leader, and counters such as Tapestry, Reweave, Seal, Fulfillment, Expose, Mimic and Corrupt
rank below ordinary cards and are rarely played.

**`faction-greedy` recipe.** It keeps the affinity table above and applies the same rules to every faction:

- *Read the board.* Outcomes rank by public band (none counts as `MEDIUM`), then by own Scan value, else printed
  weight; the rank is the share of outcomes strictly below, 0 to 100. No band edge is assumed.
- *Skip waste.* Once the service refuses a special in a window, it does not try it there again on the same event or
  participant. It never plays
  a Foresight on an event it wrote this era, a second Thread or Rewrite in an era (a Thread again only after its
  Tapestry absorbed an erasure), an Obscure in Round 3, a Reweave unless its Thread outcome fell to `LOW` with no
  Tapestry armed, a Tapestry below two confirmed links, a Fulfillment or Seal off its
  written outcome, a Mimic off its Rewrite outcome, an Expose or Corrupt against a participant the previous round's
  summary does not show playing a Probability Shifter card, or a raise toward a `LOW` outcome when the same card or
  special has another target.
- *Back its own commitment* (the declared, rewritten, threaded or written outcome): `+200` on a Push, Swing or Mimic
  toward it; *don't feed others*: `-200` toward another participant's declared outcome.
- *Play the counter on its trigger:* Tapestry, Reweave and Fulfillment (on a `HIGH` written outcome) at affinity 7,
  Seal at 6, Expose, Mimic and Corrupt at 5, all above ordinary cards.

It has no rule aimed at a faction. A change to any of these rules is a new bundle version, made only for a defect
(a wasted action, an illegal choice, a counter that never fires), never to move a win ratio.

**Paradox choice and Tapestry.** On top of the recipe:

- *Paradox cards by stake and cost, not habit.* The observation carries the table's announced paradox rules (contest
  on or off, Stabilize, Detonate and cascade values, the Tapestry rule), read from the case's effective rules. For each
  affected event the bot values clearing as its stake (3 when an outcome of the event is its own commitment) plus the
  Stabilize value, and cascading as minus that stake plus the Detonate value while Detonate contests Stabilize, or plus
  the size of the cascade penalty while it does not. It plays Stabilize when clearing is positive and better, Detonate
  when cascading is positive, and otherwise the recipe's choice with Stabilize and Detonate excluded. It does not weigh an
  imminent collapse: the projection does not say how many events have cascaded.
- *Tapestry under the current rule.* While Tapestry does not absorb the Annihilate, a second Thread is always wasted
  and Reweave stays allowed with Tapestry armed.

See `docs/balance-criteria/current.md`.

**Unavailable specials.** The bot never proposes a special the table reports unavailable this round. The
observation marks each playable special with the reason the projection's per-round special availability gives (not
eligible this era or round, era or game budget used, jammed); the bot excludes every marked special before the rest
of its recipe decides, so it stops spending attempts on specials the game would refuse. The report is advisory: a special refused for its target or a prerequisite is still excluded and retried.

**Window procedure.** The runner implements `ParticipantGateway` over the generated participant clients and drives
`DecisionWindowService`: every seat's observation is frozen and every choice computed before any submission, then
submissions go out in seat order. A rejected candidate is recorded and excluded. The budget counts
rejected candidates: while the seat's rejection count is still below the manifest's
`maxRejectedCandidatesPerWindow`, the policy reselects from a refreshed observation; once the count
reaches the budget, the seat instead submits an available pass or decline fallback, or returns
`POLICY_EXHAUSTED` (distinct from pass and decline) when none remains unexcluded. So a budget of `1`
means the first rejection goes straight to the fallback with no policy reselection, and a budget of
`2` means one policy reselection before the fallback; it never invents a legal choice. A missing acknowledgement is reconciled against accepted
state before any retry, so an accepted action is never spent twice; a seat whose accepted state never becomes current is reported as `ReconciliationPending`
(the runner must reconcile it before submitting again), and a seat shown not to have been spent after one
resubmission is reported as `NotAccepted`. Either way the results of the other seats are kept.

**Limitations.** Neither policy models authoritative resolution, scoring, or opponents, and terminal readiness has
no dedicated participant operation in the pinned contracts, so the gateway maps it to the runner's continuation
signal. Bot results characterize these baselines only and do not establish human balance.
