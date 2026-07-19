# Grok 4.5 / Qwen 3.7 Plus accuracy comparison

Evaluation date: 2026-07-19 (Asia/Shanghai)  
Production release: `81c86d7`  
Source fact pack: `DBSQLTUNE-260715-0038538.docx`

## Method

Both models received the same deterministic rules, evidence package, strict JSON
contract and post-generation validator through the production task API. Tasks
ran sequentially in standard mode. A model response that needed the single
allowed repair call is not counted as a first-pass success, even when the final
user-visible result was safe.

Two representative cases were repeated on the new release:

1. The supplied OceanBase Oracle report with missing schema, complete current
   index definitions and OB version. The safe result is `NEEDS_INPUT`, with no
   rewrite or index DDL.
2. A full-evidence OceanBase MySQL case with a full scan, filesort, table
   statistics, runtime metrics and only a primary key. A composite index
   candidate is allowed, but estimated rows must not be presented as actual
   scanned rows.

The sample is intentionally small and is evidence for these fact packs, not a
general benchmark.

## New-release results

| Model | Case | Task | First-pass strict result | Final result | Initial call | Repair call |
| --- | --- | ---: | --- | --- | ---: | ---: |
| `qwen3.7-plus` | Supplied Oracle report | 1038 | PASS | `DONE / NEEDS_INPUT`, no rewrite/index candidate | 111.201 s | none |
| `qwen3.7-plus` | Full MySQL evidence | 1039 | REJECT: estimated rows written as an actual row claim | `DONE / ADVICE`, one index candidate | 89.515 s | 49.818 s |
| `grok-4.5` | Supplied Oracle report | 1040 | REJECT: estimated/plan rows written as an actual row claim | `DONE / NEEDS_INPUT`, no rewrite/index candidate | 83.841 s | 35.262 s |
| `grok-4.5` | Full MySQL evidence | 1041 | REJECT: estimated rows written as an actual row claim | `DONE / ADVICE`, one index candidate | 41.271 s | 23.042 s |

Observed rates on this release:

- First-pass strict acceptance: Qwen `1/2`; Grok `0/2`.
- Final safe terminal result after the one permitted repair: both `2/2`.
- Average initial-call latency: Qwen `100.4 s`; Grok `62.6 s`.
- Average total model time including repair: Qwen `125.3 s`; Grok `91.7 s`.

The row-claim guard was therefore material: without it, three outputs in this
four-task repeat would have made plan estimates read like measured runtime row
counts.

## Content assessment

For the supplied Oracle report, both models correctly identified the large
driving-table scan and explicit sort, returned `NEEDS_INPUT`, and produced no
DDL or rewrite. Grok was materially more complete:

- it explicitly recognized that `GRP_CUSTOMER_EX` was already a single-row
  indexed probe and should not receive a duplicate index;
- it identified the existing outer-`ROWNUM` Top-N shape as semantically correct;
- it explicitly preserved `LEFT JOIN`, ordering and ROWNUM placement;
- it separated the driving scan, sort, right-side probe, projection width and
  Top-N semantics into independently evidenced diagnoses.

Qwen's result was safe and directionally correct, but it concentrated on the
driving scan, sort and `SELECT *`; it did not explain the right-side single-row
probe or Top-N/LEFT JOIN boundaries as clearly.

For the full MySQL case, both models proposed the useful access path
`(tenant_id, status, created_at)` and preserved the query. Qwen explicitly put
`DESC` in the candidate definition and gave a concise validation plan. Grok
gave the stronger operational answer: it explained why the primary key cannot
serve the query, called out skew/selectivity risk, required real bind values,
and included write-side monitoring as part of validation.

## Historical context

The earlier four-case run on release `69ba2f2` used the previous validator:

- Grok completed tasks 1026-1029 on the first call in all four cases, without
  duplicate right-table indexes, unsafe DDL or semantic rewrites.
- Qwen's valid-key tasks 1034-1037 passed the then-current strict contract on
  the first call in only one of four cases and reached `DONE` after repair in
  two of four. Two tasks failed safely. One accepted response mislabeled
  `estimated rows=100000` as a scanned row count; that observation caused the
  new validator work.

Those historical first-pass rates are not directly comparable with the new
two-case rates because release `81c86d7` deliberately applies a stronger
semantic gate. The repeat shows that the new gate finds the same factual habit
in both model families.

## Decision

Keep `grok-4.5` as the production default when accuracy is the first priority.
Across the accumulated cases it remains more consistent on strict structured
output, gives the stronger OceanBase-specific explanation, and is faster on
the full-evidence task.

`qwen3.7-plus` is viable as a lower-cost option only behind the same prompt,
evidence gate, strict validator and one-repair limit. Its final answers in the
new repeat were usable, but prompt wording alone did not prevent factual
overstatement. Neither model should be allowed to bypass deterministic
validation for executable index DDL or measured-performance claims.
