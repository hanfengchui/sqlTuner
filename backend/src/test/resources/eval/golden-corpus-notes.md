Generated golden corpus is implemented in `SqlAccuracyGoldenCaseTest`.

It creates 60 deterministic cases at test runtime:
- 30 OceanBase MySQL cases.
- 30 OceanBase Oracle cases.

The corpus covers SELECT, UPDATE, DELETE, joins, subqueries, aggregation,
pagination, function-wrapped columns, leading wildcard LIKE, full scans,
partition-pruning risk, temporary table risk, back-table risk, composite index
prefix checks, and DML without WHERE.
