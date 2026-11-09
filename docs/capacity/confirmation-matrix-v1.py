"""Confirmation-matrix analysis: within-seed correlation and the G-05 and G-07 ratios, per player count.

Usage: python -I confirmation-matrix-v1.py <cases.json> [bootstrap-count]

The input is the list of case records (``GET /runs/{runId}/cases/{caseId}``) of one run of the criterion version 2
confirmation matrix. A seat-game is one faction's seat in one game.

* ``rho``: the one-way random-effects intraclass correlation of one faction's seat-games, clustered by seed, as
  ``docs/capacity/five-player-analysis-v2.md`` estimates it, averaged over the factions whose win indicator varies.
  As there, the winners of a game share one win equally (a sole winner scores 1, each of two winners 1/2).
* ``G-05``: a faction's win rate on the ``faction-greedy-v1`` table over ``R`` of that table at the player count.
* ``G-07``: a faction's win rate on the random seat (seat 0) of the mixed table over ``R`` of the whole mixed table.
  The win rates and ``R`` follow ``docs/balance-criteria/v2.md``: every winner scores a whole win, and ``R`` is the
  winner seat-games of the table over its seat-games (mean winners per game over ``p``), recomputed in every resample.

Every interval is a 95% percentile bootstrap over seeds: a resampled seed keeps all its games together.
"""

import json
import math
import sys
from collections import defaultdict

import numpy as np

FACTIONS = ["ACTIVISTS", "ERASERS", "PROPHETS", "REVISIONISTS", "WEAVERS"]
TABLES = {"faction-greedy": "faction-greedy-v1", "random": "random-v1", "mixed": "random-seat-vs-faction-greedy-v1"}
SEAT_GAMES_PER_SEED = {3: 18, 4: 16, 5: 5}  # one faction, one table: seat-games per seed
Z_ALPHA, Z_BETA = 1.96, 1.28  # 95% confidence, 90% power
SEED = 1


def table_of(case):
    policies = [a["policyId"] for a in sorted(case["assignments"], key=lambda a: a["seatIndex"])]
    if len(set(policies)) == 1:
        return TABLES[policies[0]]
    if policies[0] == "random" and set(policies[1:]) == {"faction-greedy"}:
        return TABLES["mixed"]
    raise ValueError(f"unexpected seating {policies}")


def aggregate(cases):
    """Per (table, players): arrays over seeds of per-faction seat-games, wins, wins squared and seat-0 figures."""
    seeds = sorted({int(c["seed"]) for c in cases if c["state"] == "SUCCEEDED"})
    where = {s: i for i, s in enumerate(seeds)}
    shape = (len(seeds), len(FACTIONS))
    cells = defaultdict(lambda: {k: np.zeros(shape) for k in ("n", "win", "win2", "whole", "n0", "win0")})
    for case in cases:
        if case["state"] != "SUCCEEDED":
            continue
        winners = {w["faction"] for w in case["result"]["winners"]}
        credit = 1 / len(winners) if winners else 0
        cell = cells[(table_of(case), case["playerCount"])]
        s = where[int(case["seed"])]
        for a in case["assignments"]:
            f = FACTIONS.index(a["faction"])
            won = credit if a["faction"] in winners else 0
            whole = 1 if a["faction"] in winners else 0
            cell["n"][s, f] += 1
            cell["win"][s, f] += won
            cell["win2"][s, f] += won * won
            cell["whole"][s, f] += whole
            if a["seatIndex"] == 0:
                cell["n0"][s, f] += 1
                cell["win0"][s, f] += whole
    return seeds, cells


def rho(cell, idx, m):
    """Mean intraclass correlation over the factions whose win indicator varies, over the seeds ``idx``."""
    out = []
    for f in range(len(FACTIONS)):
        n, win, win2 = cell["n"][idx, f], cell["win"][idx, f], cell["win2"][idx, f]
        if not np.all(n == m):
            continue
        k = len(idx)
        means = win / m
        between = m * np.sum((means - means.mean()) ** 2) / (k - 1)
        within = np.sum(win2 - m * means**2) / (k * (m - 1))
        total = between + (m - 1) * within
        if total > 0:
            out.append((between - within) / total)
    return float(np.mean(out)) if out else math.nan


def ratios(cell, idx, seat_zero):
    """Each faction's win rate over R: on every seat, or on seat 0 only with R still over the whole table."""
    r = cell["whole"][idx].sum() / cell["n"][idx].sum()
    n, win = (cell["n0"], cell["win0"]) if seat_zero else (cell["n"], cell["whole"])
    return (win[idx].sum(axis=0) / n[idx].sum(axis=0)) / r


def interval(samples):
    samples = np.asarray(samples)
    return float(np.nanpercentile(samples, 2.5)), float(np.nanpercentile(samples, 97.5))


def design_seeds(p, rho_p):
    """Seeds G-05 needs at ``p`` players for a faction at ratio 0.8, 90% power, scaled by the design effect."""
    big_r = 1 / p
    pi = 0.8 * big_r
    m = SEAT_GAMES_PER_SEED[p]
    independent = math.ceil((Z_ALPHA + Z_BETA) ** 2 * (pi * (1 - pi) / m) / (0.3 * big_r) ** 2)
    return independent, math.ceil(independent * (1 + (m - 1) * rho_p))


def main(path, resamples):
    cases = json.load(open(path, encoding="utf-8"))
    failed = [c for c in cases if c["state"] != "SUCCEEDED"]
    seeds, cells = aggregate(cases)
    rng = np.random.default_rng(SEED)
    boot = [rng.integers(0, len(seeds), len(seeds)) for _ in range(resamples)]
    everyone = np.arange(len(seeds))
    report = {"cases": len(cases), "failed": [c["caseId"] for c in failed], "seeds": len(seeds), "rho": {}, "g05": {}, "g07": {}, "resize": {}}
    print(f"cases {len(cases)}, not succeeded {len(failed)}, seeds {len(seeds)} ({seeds[0]} to {seeds[-1]})")
    for table in (TABLES["faction-greedy"], TABLES["random"]):
        for p in (3, 4, 5):
            cell, m = cells[(table, p)], SEAT_GAMES_PER_SEED[p]
            point = rho(cell, everyone, m)
            low, high = interval([rho(cell, b, m) for b in boot])
            report["rho"][f"{table}/{p}"] = {"estimate": point, "low": low, "high": high}
            print(f"rho {table:34s} p={p}  {point:.4f}  95% [{low:.4f}, {high:.4f}]  (m={m})")
    for p in (3, 4, 5):
        cell = cells[(TABLES["faction-greedy"], p)]
        point = ratios(cell, everyone, False)
        samples = np.array([ratios(cell, b, False) for b in boot])
        for f, name in enumerate(FACTIONS):
            low, high = interval(samples[:, f])
            report["g05"][f"{p}/{name}"] = {"estimate": float(point[f]), "low": low, "high": high}
            print(f"G-05 faction-greedy-v1 p={p} {name:13s} {point[f]:.3f}  95% [{low:.3f}, {high:.3f}]")
    for p in (3, 4, 5):
        cell = cells[(TABLES["mixed"], p)]
        point = ratios(cell, everyone, True)
        samples = np.array([ratios(cell, b, True) for b in boot])
        for f, name in enumerate(FACTIONS):
            low, high = interval(samples[:, f])
            report["g07"][f"{p}/{name}"] = {"estimate": float(point[f]), "low": low, "high": high, "seatGames": int(cell["n0"][:, f].sum())}
            print(f"G-07 random seat p={p} {name:13s} {point[f]:.3f}  95% [{low:.3f}, {high:.3f}]  ({int(cell['n0'][:, f].sum())} seat-games)")
    greedy = TABLES["faction-greedy"]
    for p in (3, 4, 5):
        est = report["rho"][f"{greedy}/{p}"]
        for label, value in (("estimate", est["estimate"]), ("low", est["low"]), ("high", est["high"])):
            independent, needed = design_seeds(p, max(value, 0))
            report["resize"][f"{p}/{label}"] = {"rho": value, "independent": independent, "seeds": needed}
            print(f"re-size p={p} rho {label} {value:.4f}: n0 {independent}, seeds {needed}{'  > 104' if needed > 104 else ''}")
    json.dump(report, open(path.replace(".json", ".analysis.json"), "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 2000)
