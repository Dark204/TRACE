"""Pure paired statistics; the caller supplies independently post-seal matched ranks."""
from collections import defaultdict


SEED = 20261118
DRAWS = 20_000
METRICS = ('Top1', 'Top3', 'Top5', 'MRR@5')
BASE = 60  # 60 / rank is exact integer for each possible nonzero reciprocal rank.


def unit_scores(rank):
    assert rank is None or type(rank) is int and rank >= 1
    return (BASE * int(rank == 1),
            BASE * int(rank is not None and rank <= 3),
            BASE * int(rank is not None and rank <= 5),
            BASE // rank if rank is not None and rank <= 5 else 0)


def exact_signed_sum(values):
    """Two-sided exact paired label-swap test including ties in the tail."""
    weights = [abs(v) for v in values if v]
    observed = abs(sum(values))
    states = {0: 1}
    for w in weights:
        expanded = defaultdict(int)
        for s, n in states.items():
            expanded[s - w] += n
            expanded[s + w] += n
        states = expanded
    extreme = sum(count for v, count in states.items() if abs(v) >= observed)
    return extreme / (1 << len(weights))


def holm(values):
    ordered = sorted(range(len(values)), key=lambda i: (values[i], i))
    out = [0.] * len(values)
    floor = 0.
    for i, which in enumerate(ordered):
        floor = max(floor, min(1., values[which] * (len(values) - i)))
        out[which] = floor
    return out


def summarize(rows, key):
    ranks = [unit_scores(r[key]) for r in rows]
    counts = [sum(v) for v in zip(*ranks)] if ranks else [0] * 4
    n = len(rows)
    return dict(n=n, counts={k: counts[i] // BASE for i, k in enumerate(METRICS[:3])},
                means={k: v / (BASE * n) if n else None for k, v in zip(METRICS, counts)})


def paired(rows, a='e', b='flexfl'):
    import numpy as np

    rows = sorted(rows, key=lambda x: x['case'])
    assert len({x['case'] for x in rows}) == len(rows)
    if not rows:
        return []
    mat = [[u-v for u, v in zip(unit_scores(r[a]), unit_scores(r[b]))] for r in rows]
    vals = np.asarray(mat, dtype=np.int64)
    generator = np.random.default_rng(SEED)
    indices = generator.integers(0, len(rows), size=(DRAWS, len(rows)))
    draws = vals[indices].mean(axis=1) / BASE
    bounds = np.quantile(draws, [0.025, 0.975], axis=0).T.tolist()
    ds = (vals.mean(axis=0) / BASE).tolist()
    ps = [exact_signed_sum([v[j] for v in mat]) for j in range(4)]
    return [dict(metric=m, n=len(rows), difference=d, bootstrap95=ci, exact_p=p, holm4=h,
                 draws=DRAWS, seed=SEED, test='two-sided exact pairwise permutation, integer summed effects')
            for m, d, ci, p, h in zip(METRICS, ds, bounds, ps, holm(ps))]
