# TRACE: Trace-Scoped Relevance Assessment With Calibrated Evidence for Method-Level Fault Localization

TRACE ranks candidate methods using a failing test, its execution coverage, and the program source. This repository contains the implementation and data.

## Run one case

Use Python 3.12, JDK 11, ASM 3.3.1 and the matching Defects4J and JUnit dependencies. Install `requirements-inference.txt` and a bf16-capable CUDA PyTorch build matching [the runtime configuration](data/runtime.json). The buggy checkout and local UniXcoder and Qwen3-Reranker-4B snapshots are external inputs; [the model manifest](data/model.json) pins the reranker files.

```sh
python -m pip install -r requirements-inference.txt
python -B -m trace_release.run_case --case Cli-37 \
  --classpath "$CASE_CLASSPATH" --source-root "$SRC" \
  --production-classes "$PROD_CLASSES" --test-source "$TEST_SOURCE" \
  --triggers "$TRIGGERS" --unixcoder "$UNIXCODER" --qwen "$QWEN4B" \
  --out "$NEW_OUTPUT"
```

Point the arguments at the compiled buggy revision, the source defining the official triggering test, the official trigger list, and the model snapshots. Build `CASE_CLASSPATH` from absolute paths to production classes, test classes, ASM, JUnit and project dependencies. The output directory must not exist. Use `--help` for JDK, JUnit and trigger selection.

## Evaluate locally

```sh
python -m pip install -r requirements-tables.txt
python -B -m trace_release.tables --main-table
```

See [data sources](data/SOURCES.md) for provenance.
