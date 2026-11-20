# Alighting reproduction check (R1 4-1, 2026-11-20)

Hourly OD on the site is the daily OD split by the boarding stop's hourly
profile: riders boarding at a stop in hour *h* are assumed to share one time
profile whatever their destination. Boardings by hour then match by
construction, so the test is the other end: rebuild hourly **alightings** from
the split and compare them with the counted hourly alightings.

Query: `transit-bigquery/sql/checks/alight_repro.sql` (key check:
`alight_repro_keys.sql`). Data: typical-day bus OD of the window
2025-10..2026-09 (`od_typical_route_stop_serving`) times the days of each
type in the window, split by `hourly_profile` (default profile where a stop
has none), against `fct_boarding` (monthly hourly boardings and alightings
per route and stop) over the same twelve months. 658 routes; route, boarding
and alighting keys meet for 98.3-98.6% of riders; 99.1% of riders have stop
sequences.

Each rider is moved forward by the ride: (alighting seq - boarding seq) x *m*
minutes, boardings spread evenly over the hour. Levels differ between OD and
the counts (correction, transfers), so each route, or each route and stop, is
scaled to its counted total first: the errors are about the shape over the
day. WAPE = sum |predicted - counted| / sum counted.

| | route x hour | stop x hour | 08:00 by route |
|---|---|---|---|
| boardings (control, should be near 0) | **6.9%** | **17.3%** | 4.5% |
| alightings, no ride time (*m* = 0) | 8.5% | 22.3% | 7.1% |
| alightings, *m* = 1.5 min/stop | **7.6%** | **21.7%** | 8.5% |
| alightings, *m* = 2.0 | 7.7% | 21.7% | 9.1% |
| alightings, *m* = 2.5 | 7.9% | 21.8% | 9.8% |

Reading:

- The boarding control is not zero: the profiles are solved over 34 months
  and every day type, the comparison is one year with all days mixed, and the
  typical OD's stop totals differ from the counts. That is the floor.
- The destination-independent split adds about **0.7 points** by route and
  hour and **4.4 points** by stop and hour over that floor (best ride time
  1.5 minutes a stop; median ride 12 stops).
- Citywide, the morning peak alightings come out too low (08:00: 7.5% of the
  day predicted, 8.1% counted) and late night too high (00:00: 1.2% vs
  0.8%); the evening peak is low for boardings and alightings alike, so it is
  the profile, not the split.
- The routes with the largest errors (village buses such as 관악02-1,
  동작07, 금천11-1: 25-57%) have the same size of error on the boarding
  control, so the profile or the OD of those routes is off, not the split.

Decision: the split stays for R1. Fitting both ends (IPF: per route, adjust
until boarding-stop and alighting-stop hourly totals both match) would at
most remove the 0.7 / 4.4 points; it goes to R2, below fixing the village
routes' profiles.
