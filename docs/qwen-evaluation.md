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
| 21 | Production model connection after MySQL migration and data-key rotation | Real `qwen3.7-max` response succeeded in 3.664 s; migrated encrypted API key was readable with the rotated key. |
| 22-24 | First production standard smoke at migrated 30 s timeout | Three read timeouts exposed that the full structured report regularly exceeds the legacy timeout. Production timeout was raised to 120 s; the worker reached a safe `FAILED` terminal state after the configured retry budget. |
| 25 | Standard smoke after timeout correction | `DONE` in one request with `ADVICE`; SSE disconnect recovered through authoritative task GET. |
| 26-28 | First production deep smoke | Initial analysis completed, but the reviewer returned an invalid full-result shape and its one repair was still invalid JSON. The task failed safely. Deep review was changed to a compact `PASS/REVISE/REJECT` envelope and text calls now use DashScope JSON mode. |
| 29 | Final production standard smoke with JSON mode | `DONE` in one request with 3 diagnoses, 2 index candidates and 8 durable artifacts; SSE snapshot and GET recovery passed. |
| 30-32 | Lease-race production deep smoke | Initial analysis and reviewer ran, but a stale in-memory lease overwrote heartbeat renewals and allowed one duplicate analysis call. Optimistic locking prevented duplicate completion. State transitions were changed so they can extend or clear, but never shorten, a renewed lease. |
| 33-34 | Final production deep smoke after lease correction | `DONE` on one worker attempt with `ADVICE` and reviewer verdict `REVISE`; 3 diagnoses, 1 index candidate and 9 durable artifacts. Queue returned to zero running/queued tasks. |

Current usage: **34 / 60**. Remaining budget: **26**.

Because the first nine diagnostic requests were real external calls, the
original target of 40 standard tasks plus 10 deep tasks can no longer fit under
the 60-request hard limit. The final run must prefer the hard limit, maximize
dialect/scenario coverage inside the remaining budget, and report the achieved
standard/deep counts without reclassifying or hiding diagnostic calls.
