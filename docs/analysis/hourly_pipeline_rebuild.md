# Hourly pipeline rebuild: defects found and validation

> Moved from the project README. The README keeps a summary and the validation table.

Extending the hourly boarding table from six months (2025-01 to 2025-06) to
thirty-two (2024-01 to 2026-08) surfaced four defects that had been producing
plausible numbers. None raised an error. Each was found by reconciling a figure
against something independent of the pipeline that produced it.

**The loader overwrote passengers.** The table key is
`(use_ym, route_no, stop_ars, hour)`, but the source API returns one row per
stop *visit*. A route that passes the same stop twice — near its origin and
again at its turnaround — arrives as two rows with the same stop ID and ARS code
and different sequence numbers, and the upsert kept only one of them. On route
동대문01 at 회기역 the two rows for 2024-01 carried 97,271 and 3,389 boardings;
the table held one of those figures instead of their sum. Every month 3,400–3,900
of roughly 42,000 source rows collided this way, and 2024-01 boarding was
understated by 4,518,304 (3.6%). Row counts were unaffected, so a row-for-row
reconciliation would not have caught it.

Two checks ruled out the other explanation, that the API was sending duplicates:
none of the 3,328 colliding groups had identical values, and every group shared a
single stop ID. The loader now sums a whole month before writing — summing per API
page is not enough, because a collision can straddle a page boundary — and the
upsert still assigns rather than adds, so re-running a month stays idempotent.

**The holiday table had wrong dates.** Day-type counts per month are the design
matrix of the ratio model, so they were checked against the calendar: each count,
not just the monthly total. Six months failed. 2024 Lunar New Year was recorded
as January 9–12 instead of February 9–12, the 2024-10-01 temporary holiday was
missing, and the 2025-01-27 temporary holiday was recorded as the 24th. The 2026
rows listed substitute holidays but omitted the holidays that fell on weekends;
two of those were Saturdays, which shifted the June and August counts. A sum
check alone would have passed all six.

Manual review is not a method, so the calendar is now asserted by tests: every
fixed-date holiday in every year the CSV covers, and lunar holidays against
dates computed by a separate calendar implementation rather than the CSV itself.
Those tests immediately found two more omissions the manual pass had missed —
2026-09-26 and 2026-10-03, both Saturdays, the same defect class as the June and
August rows. Both fall outside the loaded range, so no fitted profile changed.

**The fallback ratio did not sum to one.** OD rows with no fitted hourly profile
are spread across the day with a hand-written 24-hour profile. It summed to 1.105,
inflating every fallback estimate by 10.5%. It is now normalised.

**A fitted profile of all zeros dropped passengers silently.** Clipping negative
least-squares estimates to zero leaves 2,327 of 283,353 ratio keys summing to
zero rather than one. Those keys exist, so the estimator preferred them over the
fallback and multiplied the day's passengers by zero: 18,965 of 5,345,649 daily
OD passengers on 2025-11-11 reached no hour at all. Profile selection now has
three levels, and each row records which one applied — a fitted profile when one
sums to one, the normalised fallback as `default_static_ratio_zero_fit` when the
key exists but fitted to zeros, and the same fallback as `default_static_ratio`
when no key exists. The two fallback cases share their numbers but not their
meaning: the first is a route-stop that was observed and could not be decomposed,
the second was never fitted at all, and merging them would hide which population
is growing.

**Virtual stops leaked into the ratio model.** The filter excluded
`stop_ars = '00000'`, but the API marks virtual stops with `~`, which passed
through into 24,696 rows in each derived table. The filter now accepts only
five-digit codes other than `00000`.

The rebuild also changed the model's footing. The solver estimates seven day-type
averages per route-stop-hour from monthly totals. With six months it had at most
six equations for seven unknowns, so every earlier fit was the minimum-norm
solution of an underdetermined system; with thirty-two months the system is
overdetermined. The solver now processes routes in batches and solves all groups
that observed the same months in one least-squares call — equivalent to the
per-group loop, verified against the original on synthetic data — which keeps a
30-million-row input within a machine with 8 GB of RAM. Because the estimation
method is part of the hourly OD table's key, a row moving from the fallback to a
fitted profile would otherwise be kept twice; the estimator now replaces a date
range inside one transaction.

| Check after rebuild | Result |
|---|---|
| Re-loaded months: row counts against the loader log | 24 of 24 match |
| Hourly ratio keys summing to exactly 1 or 0 | 283,353 of 283,353 |
| Non-numeric or `00000` ARS codes in derived tables | 0 |
| Duplicate OD-hour rows across estimation methods | 0 |
| OD keys in the estimate against the source | 514,499; 0 missing, 0 extra |
| Hours per OD key | 24 distinct, no exceptions |
| Daily passengers conserved per OD key | 0 mismatches at 1e-07 |
| Daily OD passengers not distributed to hours | 0 of 5,345,649 |
| Fallback-profile rows, 2025-11-11 | 243,960 — 181,272 with no key, 62,688 fitted to zeros |
| Estimator re-run: row count and SHA-256 over every row | identical |

Fallback rows rose from 181,272 because 2,612 OD keys that previously lost their
passengers to a zero profile are now distributed; the figure to read alongside it
is the zero in the row above.

The totals are reconciled per OD key rather than in aggregate. A per-key
tolerance of 1e-07 passengers catches errors that cancel out in a 5.3-million
sum, and missing keys, extra keys and missing hours are separate checks rather
than a difference in one total. `validate_rebuild.py` runs all of them and exits
non-zero, so a rebuild that printed success but left the data wrong fails the
gate instead of the reader.
