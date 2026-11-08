# Hourly pipeline rebuild: defects found and validation

> Moved from the project README. The README keeps a summary and the validation table.
> The sections below describe the 32-month rebuild of October 2026. The rebuild in
> Cloud SQL on 34 months, November 2026, is at the [end](#rebuilt-in-cloud-sql-november-2026).

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

## Rebuilt in Cloud SQL, November 2026

Cloud SQL became the source of record in November 2026, and the whole chain was rebuilt
there: first step by step by hand on 6–7 November, then as one forced run of the weekly
workflow on 7 November, which took 9 h 30 min. The hourly table then held 34 months
(2024-01 to 2026-10, 31,739,712 rows). Both runs gave the same result.

| Check | 34-month rebuild |
|---|---|
| Hourly ratio keys summing to exactly 1 or 0 | 285,201 of 285,201 |
| Ratio keys fitted to all zeros, routed to the fallback profile | 2,217 (0.78%) |
| Non-numeric or `00000` ARS codes in derived tables | 0 |
| Duplicate OD-hour rows across estimation methods | 0 |
| OD keys in the estimate against the source | 514,499; 0 missing, 0 extra |
| Hours per OD key | 24 distinct, no exceptions |
| Daily passengers conserved per OD key | 0 mismatches at 1e-07 |
| Daily OD passengers, source and estimate | 5,345,649 and 5,345,649 |
| Rows by profile, 2025-11-11 | 12,143,088 fitted; 181,272 no key; 23,616 fitted to zeros |
| Hourly OD rows and SHA-256, manual run against workflow run | 12,347,976 rows, identical hash |

With two more months, fewer keys fit to zeros (2,217 against 2,327) and the rows
falling back for that reason dropped from 62,688 to 23,616. Rows with no key at all are
unchanged at 181,272, so the extra months gave none of those route-stop pairs a
profile; why each lacks one has not yet been broken down.

The hourly OD table is an allocation of one day's OD records over the hours, using these
profiles; it is not an observation of when those trips were made. The README section
*What the hourly OD is* sets out the assumption and what the checks above do and do not
show.

Two operational findings from the cloud runs shaped the workflow. On the shared-core
instance that serves the API, one batch of 50 routes took 7–20 minutes, so the workflow
raises the instance tier only for the rebuild; the solver job itself ran out of memory at
2 GiB and now runs with 8 GiB and batches of 25 routes. And the Cloud SQL connection
can drop mid-run while the database keeps running; each route batch now commits on its
own and is retried, so a dropped connection costs one batch, not the run.
