# Qwen production evaluation ledger

Hard limit: at most 60 real DashScope requests for this rebuild. Timeouts and
provider-side 4xx rejections count conservatively because they reached the
external API.

| Requests | Scenario | Result |
| ---: | --- | --- |
| 1-6 | Initial real-model timeout diagnosis | Six read timeouts at the former 30 s limit |
| 7-9 | First full-context standard task | Three valid responses wrapped in a Markdown JSON fence; the old parser rejected them |
| 10 | Standard task after the fence fix | `DONE` in one request, 83.857 s, no repair call |
| 11 | `qwen3.7-max` image capability probe | HTTP 400: image content items are not supported by this model |
| 12 | `qwen3-vl-plus` plan screenshot probe | Success; extracted OceanBase operators, table/index text, and row estimates |
| 13-18 | First end-to-end pasted-report plus screenshot task | Three worker attempts each made one vision call and one vision repair call; all six responses missed the former rigid DTO shape. The task failed before text analysis, exposing that JSON parse causes were incorrectly classified as retryable network errors. Parser normalization and retry classification were corrected before the next evaluation. |
| 19-20 | Pasted-report plus screenshot after parser/retry correction | `DONE` on the first worker attempt with exactly one `qwen3-vl-plus` call and one `qwen3.7-max` call. Report metrics and the image BLOB were retained; image facts entered as LOW-trust `E_PLAN_IMAGE`; without schema/index/text EXPLAIN the final result correctly stayed `NEEDS_INPUT`, LOW confidence, with zero rewrite/index candidates. |

Current usage: **20 / 60**. Remaining budget: **40**.

Because the first nine diagnostic requests were real external calls, the
original target of 40 standard tasks plus 10 deep tasks can no longer fit under
the 60-request hard limit. The final run must prefer the hard limit, maximize
dialect/scenario coverage inside the remaining budget, and report the achieved
standard/deep counts without reclassifying or hiding diagnostic calls.
