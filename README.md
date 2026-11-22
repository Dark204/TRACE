# TRACE: Trace-Scoped Relevance Assessment With Calibrated Evidence for Method-Level Fault Localization

Final implementation and data for 347 bugs from all 17 Defects4J projects; AutoFL covers 252 of these bugs.

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

See [baseline data sources](data/SOURCES.md). The data use one case roster. Each ranking file stores a method dictionary and zero-based indices in rank order.

## Matched results

| Method | 347: Top-1 / 3 / 5 | MRR@5 | 252: Top-1 / 3 / 5 | MRR@5 |
|---|---:|---:|---:|---:|
| BoostN | 78 / 117 / 134 | 0.282 | 57 / 82 / 89 | 0.274 |
| Ochiai | 102 / 167 / 199 | 0.396 | 76 / 127 / 153 | 0.411 |
| SBIR | 104 / 171 / 195 | 0.401 | 79 / 124 / 139 | 0.407 |
| FlexFL (Agent4SR) | 111 / 154 / 164 | 0.384 | 80 / 112 / 122 | 0.384 |
| FlexFL | 166 / 215 / 236 | 0.556 | 121 / 157 / 171 | 0.557 |
| AutoFL GPT-3.5 (1 run) | — | — | 82 / 105 / 117 | 0.379 |
| AutoFL GPT-3.5 (5 runs) | — | — | 114 / 141 / 150 | 0.511 |
| AutoFL GPT-4 (1 run) | — | — | 129 / 160 / 172 | 0.579 |
| AutoFL GPT-4 (2 runs) | — | — | 144 / 182 / 189 | 0.644 |
| LLM+Test (5 runs) | — | — | 75 / 98 / 112 | 0.350 |
| TRACE | 170 / 246 / 264 | 0.596 | 132 / 184 / 194 | 0.623 |

A dash means the configuration is unavailable for the full panel. Each cell uses the same bugs and repaired-method mapping as the other methods in its panel.
