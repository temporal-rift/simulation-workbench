"""Monte Carlo check of the confirmation rule of balance criterion version 2 (decision D-09 of v2.md).

Faction lines of the faction-greedy-v1 table only (G-05 gates and L-01 scored lines), with the verdict logic of the
criterion: a line MEETS when its 95% interval lies inside the band and FAILS when it lies wholly outside.

Model: per seed and faction a latent log-strength shift u ~ N(0, tau^2), shared by all of that faction's games in the
seed, gives the within-seed correlation of seat-games; tau is calibrated so that the correlation of one faction's five
seat-games at five players equals rho. Each game's single winner is drawn by softmax over the seated factions'
strengths. The full matrix per seed is p=3: 10 sets x 3 rotations, p=4: 5 x 4, p=5: 1 x 5. The ratio of a faction is
its win rate over R, winner seat-games over seat-games. The interval is the percentile block bootstrap over seeds with
2,000 resamples, recomputing the ratio and R in each resample, as the workbench does.

The last section gives the normal-approximation power of G-07 (random seat, ratio <= 1.0) at five players, where each
faction sits on the random seat once per seed, so its seat-games are independent across seeds.

Run: python v2-sample-check.py [replicates]   (400 by default; v2-sample-check.txt is the output of the default run)
Requires NumPy. The random generator is seeded, so the output is reproducible.
"""
import itertools
import sys

import numpy as np

F = 5
SETS = {p: list(itertools.combinations(range(F), p)) for p in (3, 4, 5)}
B = 2000


def games_of(p):
    """Rows: games of one seed (set repeated per rotation); seats are irrelevant to the winner."""
    rows = []
    for s in SETS[p]:
        rows += [s] * p
    return np.array(rows)


GAMES = {p: games_of(p) for p in (3, 4, 5)}


def play(rng, a, tau, seeds, p):
    """wins[seed, faction], seatgames[seed, faction], winners_total[seed], seats_total[seed]."""
    g = GAMES[p]
    u = rng.normal(0.0, tau, size=(seeds, F))
    strength = np.exp(a[None, :] + u)  # seeds x F
    st = strength[:, g]  # seeds x games x p
    prob = st / st.sum(axis=2, keepdims=True)
    cum = prob.cumsum(axis=2)
    r = rng.random((seeds, g.shape[0], 1))
    idx = (r > cum).sum(axis=2)  # winner position in game
    idx = np.minimum(idx, p - 1)
    winner = g[np.arange(g.shape[0])[None, :], idx]  # seeds x games
    wins = np.zeros((seeds, F))
    for f in range(F):
        wins[:, f] = (winner == f).sum(axis=1)
    seat = np.zeros(F)
    for row in g:
        seat[list(row)] += 1
    return wins, np.tile(seat, (seeds, 1))


def calibrate(target_share_ratio, tau, p, rng, n=4000, iters=60):
    """a such that expected ratio of each faction equals the target at player count p."""
    a = np.log(np.asarray(target_share_ratio, float))
    for _ in range(iters):
        w, s = play(rng, a, tau, n, p)
        R = w.sum() / s.sum()
        ratio = (w.sum(0) / s.sum(0)) / R
        a += 0.8 * (np.log(target_share_ratio) - np.log(ratio))
        a -= a.mean()
    return a


def icc_parity(tau, rng, n=20000):
    """ICC of one parity faction's 5 seat-games at p=5 (the quantity measured as 0.079)."""
    a = np.zeros(F)
    g = GAMES[5]
    u = rng.normal(0.0, tau, size=(n, F))
    st = np.exp(a[None, :] + u)[:, g]
    prob = st / st.sum(axis=2, keepdims=True)
    q = prob[:, :, 0].mean(axis=1)  # faction 0's per-seed win probability (same in all 5 games)
    pi = q.mean()
    return q.var() / (pi * (1 - pi))


def tau_for(rho, rng):
    lo, hi = 0.0, 3.0
    for _ in range(40):
        mid = (lo + hi) / 2
        if icc_parity(mid, rng) < rho:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def weights(rng, seeds):
    W = np.zeros((B, seeds))
    picks = rng.integers(0, seeds, size=(B, seeds))
    for b in range(B):
        W[b] = np.bincount(picks[b], minlength=seeds)
    return W


def intervals(W, wins, seat):
    wf = W @ wins  # B x F
    sf = W @ seat
    R = wf.sum(1, keepdims=True) / sf.sum(1, keepdims=True)
    rep = (wf / sf) / R
    rep.sort(axis=0)
    lo = rep[int(np.ceil(0.025 * B)) - 1]
    hi = rep[int(np.ceil(0.975 * B)) - 1]
    point = (wins.sum(0) / seat.sum(0)) / (wins.sum() / seat.sum())
    return lo, hi, point


def verdicts(lo, hi):
    g05_meets = (lo >= 0.5) & (hi <= 2.0)
    g05_fails = (hi < 0.5) | (lo > 2.0)
    l01_fails = (hi < 0.8) | (lo > 1.25)
    return g05_meets, g05_fails, l01_fails


def run(name, target, seeds, rho, reps, rng):
    tau = tau_for(rho, rng)
    tp = target if isinstance(target, dict) else {p: target for p in (3, 4, 5)}
    a = {p: calibrate(tp[p], tau, p, rng) for p in (3, 4, 5)}
    confirmed = 0
    rejected = 0
    meet_by = {p: np.zeros(F) for p in (3, 4, 5)}
    cover = {p: np.zeros(F) for p in (3, 4, 5)}
    halfw = {p: [] for p in (3, 4, 5)}
    for _ in range(reps):
        ok = True
        rej = False
        for p in (3, 4, 5):
            wins, seat = play(rng, a[p], tau, seeds, p)
            W = weights(rng, seeds)
            lo, hi, _ = intervals(W, wins, seat)
            m, f, l = verdicts(lo, hi)
            meet_by[p] += m
            cover[p] += (lo <= np.asarray(tp[p])) & (np.asarray(tp[p]) <= hi)
            halfw[p].append(((hi - lo) / 2).mean())
            ok &= bool(m.all() and not f.any() and not l.any())
            rej |= bool(f.any())
        confirmed += ok
        rejected += rej
    print(f"{name}: seeds={seeds} rho={rho} tau={tau:.3f} reps={reps}")
    print(f"  P(all faction gates MEET and no faction line FAILS) = {confirmed / reps:.3f}; P(REJECTED) = {rejected / reps:.3f}")
    for p in (3, 4, 5):
        print(f"  p={p}: P(G-05 MEETS) by faction {np.round(meet_by[p] / reps, 3)}; "
              f"coverage {np.round(cover[p] / reps, 3)}; mean half-width {np.mean(halfw[p]):.3f}")
    sys.stdout.flush()


def g07_power(ratio, seeds=104, reference=0.2, limit=1.0, z=1.96):
    """P(the upper bound of the G-07 interval lies at or below the limit) for a faction at the given random-seat ratio.

    One seat-game per seed at five players, win probability ratio x R; the bound is the normal-approximation upper
    bound of the ratio, with the variance at the true value.
    """
    from math import erf, sqrt

    pi = ratio * reference
    se = sqrt(pi * (1 - pi) / seeds) / reference
    x = (limit - ratio) / se - z
    return 0.5 * (1 + erf(x / sqrt(2)))


if __name__ == "__main__":
    rng = np.random.default_rng(20261106)
    reps = int(sys.argv[1]) if len(sys.argv) > 1 else 400
    print("check ICC at calibrated tau for 0.079:", round(icc_parity(tau_for(0.079, rng), rng), 4))
    on_target = [0.8, 0.9, 1.0, 1.1, 1.2]
    for seeds in (104, 77, 50):
        run("on target (0.8..1.2)", on_target, seeds, 0.079, reps, rng)
    run("on target, rho at its upper bound", on_target, 104, 0.175, reps, rng)
    run("parity", [1.0] * 5, 104, 0.079, reps, rng)
    on = [0.8, 0.9, 1.0, 1.1, 1.2]
    run("edge: one faction at 0.5 at p=5 only", {3: on, 4: on, 5: [0.5, 1.0, 1.1, 1.2, 1.2]}, 104, 0.079, reps, rng)
    run("defective: one faction at 0.4 at p=5 only", {3: on, 4: on, 5: [0.4, 1.05, 1.15, 1.2, 1.2]}, 104, 0.079, reps, rng)
    print("G-07 at five players, 104 seeds, R = 0.2: P(MEETS) by random-seat ratio")
    for ratio in (0.25, 0.5, 0.6, 0.7):
        print(f"  ratio {ratio}: {g07_power(ratio):.2f}")
