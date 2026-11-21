"""Verify per-case data and evaluate the released rankings locally."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

from .statistics import paired, summarize

DATA = Path(__file__).resolve().parents[1] / "data"
ARMS = ("e", "a", "f", "b")


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def verified_cases():
    seal = json.loads((DATA / "seal.json").read_bytes())
    tables = {}
    for key, expected in seal["data_sha256"].items():
        raw = (DATA / f"{key}.json").read_bytes()
        assert sha(raw) == expected, key
        tables[key] = json.loads(raw)
    seen = set()
    for row in tables["cases"]:
        case = row["case"]
        assert case not in seen and set(row["ranks"]) == set(ARMS), case
        seen.add(case)
        targets = set(tables["truth"][case])
        assert targets, case
        path = DATA / "rankings" / f"{case}.json.gz"
        if row["status"] == "completed":
            raw = path.read_bytes()
            assert sha(raw) == seal["rankings"][case]["sha256"], case
            rank = json.loads(gzip.decompress(raw))
            assert rank["case"] == case and rank["nD"] == row["nD"], case
            names = rank["methods"]
            assert len(names) == len(set(names)) and set(rank["orders"]) == set(ARMS), case
            expected = set(range(len(names)))
            for a, order in rank["orders"].items():
                assert len(order) == len(names) and set(order) == expected, (case, a)
                got = next((i for i, j in enumerate(order, 1) if names[j] in targets), None)
                assert got == row["ranks"][a], (case, a, got)
        else:
            assert not path.exists() and case not in seal["rankings"]
            assert all(r is None for r in row["ranks"].values())
        yield {"case": case, **row["ranks"], "baselines": tables["competitors"][case]["ranks"]}
    assert len(seen) == seal["cases"] == 301
    assert seen == tables["truth"].keys() == tables["competitors"].keys()
    assert {r["case"] for r in tables["cases"] if r["status"] == "completed"} == seal["rankings"].keys()
    assert {p.name for p in (DATA / "rankings").iterdir()} == {f"{r}.json.gz" for r in seal["rankings"]}


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--check", action="store_true", help="verify the data without printing metrics")
    args = cli.parse_args()
    rows = list(verified_cases())
    if args.check:
        print(f"Verified {len(rows)} cases.")
        return
    metrics = {a: summarize(rows, a) for a in ARMS}
    comparisons = {}
    for baseline in sorted({m for r in rows for m in r["baselines"]}):
        paired_rows = [{**r, "rival": r["baselines"][baseline]} for r in rows if baseline in r["baselines"]]
        comparisons[baseline] = dict(trace=summarize(paired_rows, "e"),
                                     baseline=summarize(paired_rows, "rival"),
                                     paired=paired(paired_rows, "e", "rival"))
    print(json.dumps(dict(trace=metrics, comparisons=comparisons), indent=2))


if __name__ == "__main__":
    main()
