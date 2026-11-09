"""Load hourly boarding demand into a labelled 3-D array and profile it by terrain.

The source is already three-dimensional -- (month, hour, stop) -- so it belongs in
xarray rather than in a flat frame. Slope becomes a non-dimension coordinate on the
stop axis, which is what lets a terrain grouping be one call instead of a pivot.

    python pipelines/analysis/demand_xarray.py --source bigquery   # the warehouse (preferred)
    python pipelines/analysis/demand_xarray.py                     # PIPELINE_DB_URL, or PG* variables
    python pipelines/analysis/demand_xarray.py --netcdf ""         # skip writing demand.nc

The warehouse is the intended source: it holds the same data, copied from Cloud
SQL by the weekly pipeline and reconciled per month by dbt, and aggregating 30
million rows there takes seconds. Reading the serving database from a workstation
took hours on its idle tier and lost the connection twice (2026-11-09).

At the end it prints the slope-band table in the form the README uses, so the
README figures can be replaced by a run rather than by hand.
"""

from __future__ import annotations

import argparse
import os
import sys
import time

import numpy as np
import pandas as pd
import xarray as xr

BAND_EDGES = [0, 4, 8, 12, np.inf]
BAND_LABELS = ["0-4%", "4-8%", "8-12%", "12%+"]
NO_TERRAIN = "no terrain"
README_HOURS = [4, 5, 7, 8, 18, 22]

# Aggregate away route_no in SQL, one month per query. The analysis is per stop,
# and pulling every route row to sum them in memory would be the wrong side of the
# pipeline to do it on. One query over all 34 months ran two hours on the idle
# Cloud SQL tier and then lost its connection (2026-11-09); a month is found
# through the primary key, which starts with use_ym, and aggregates in seconds.
MONTHS_SQL = "SELECT use_ym FROM month_day_count ORDER BY use_ym"

DEMAND_SQL = """
SELECT use_ym,
       hour,
       stop_ars,
       sum(boarding_passengers)::bigint  AS boardings,
       sum(alighting_passengers)::bigint AS alightings
FROM hourly_bus_stop_passenger
WHERE use_ym = %(use_ym)s
GROUP BY use_ym, hour, stop_ars
"""

# Slope comes from the terrain snapshot via the stop master. Stops that do not
# match keep a null slope rather than being dropped -- same reasoning as
# match_method in the warehouse: totals have to stay reconcilable.
SLOPE_SQL = """
SELECT l."정류장번호" AS stop_ars,
       t.slope_pct,
       t.elev_m
FROM bus_stop_location l
JOIN bus_stop_terrain_stats t ON t.node_id = l."노드id"
"""

# Counted separately from DEMAND_SQL, so the reconciliation does not reuse the
# numbers it is checking.
TOTAL_SQL = ("SELECT sum(boarding_passengers)::bigint AS t FROM hourly_bus_stop_passenger "
             "WHERE use_ym = %(use_ym)s")

# BigQuery: the fact table for demand, the stop dimension for terrain (already
# limited to the active terrain dataset), and the 1:1 staging copy of the source
# for the reconciliation total, so the check does not read the table it checks.
BQ_PROJECT = os.environ.get("BQ_PROJECT", "project-9c77d932-f0d2-47bb-947")
BQ_DEMAND_SQL = """
SELECT CAST(service_ym AS STRING) AS use_ym,
       hour,
       stop_key AS stop_ars,
       SUM(boardings)  AS boardings,
       SUM(alightings) AS alightings
FROM `{project}.{marts}.fct_boarding`
GROUP BY 1, 2, 3
"""
BQ_SLOPE_SQL = """
SELECT stop_key AS stop_ars, slope_pct, elev_m
FROM `{project}.{marts}.dim_stop`
WHERE slope_pct IS NOT NULL
"""
BQ_TOTAL_SQL = """
SELECT CAST(ROUND(SUM(boarding_passengers)) AS INT64) AS t
FROM `{project}.{staging}.hourly_bus_stop_passenger`
"""


def db_url_from_env() -> str:
    """PIPELINE_DB_URL if set, otherwise the PG* variables the script used before."""
    url = os.environ.get("PIPELINE_DB_URL")
    if url:
        return url
    password = os.environ.get("PGPASSWORD")
    if not password:
        sys.exit("Set PIPELINE_DB_URL, or PGPASSWORD (with PGUSER/PGHOST/PGPORT/PGDATABASE).")
    return (
        f"postgresql+psycopg2://{os.environ.get('PGUSER', 'postgres')}:{password}"
        f"@{os.environ.get('PGHOST', 'localhost')}:{os.environ.get('PGPORT', '5432')}"
        f"/{os.environ.get('PGDATABASE', 'Seoul_Transit')}"
    )


def slope_bands(slope_pct) -> np.ndarray:
    """
    Band label per stop. A stop without terrain gets NO_TERRAIN, so it stays in the
    array and in the totals but in no slope band.

    The lowest band includes 0%: flat cells exist, and an open lower edge would have
    put them in no band at all, as the first version of this script did.
    """
    bands = pd.cut(pd.Series(slope_pct, dtype=float), bins=BAND_EDGES, labels=BAND_LABELS,
                   include_lowest=True)
    return bands.astype(object).where(bands.notna(), NO_TERRAIN).to_numpy()


def build_dataset(demand: pd.DataFrame, slope: pd.DataFrame) -> xr.Dataset:
    """
    Full (month, hour, stop) grid with slope, elevation and band on the stop axis.

    Combinations absent from the source become NaN, which is correct: a stop with no
    service at 03:00 is a missing observation, not a zero. Anything that sums over
    it must use skipna.
    """
    df = demand.copy()
    df["month"] = pd.to_datetime(df["use_ym"].astype(str).str.strip() + "01", format="%Y%m%d")
    df = df.drop(columns="use_ym")
    ds = df.set_index(["month", "hour", "stop_ars"]).to_xarray()

    terrain = slope.drop_duplicates("stop_ars").set_index("stop_ars").reindex(ds.stop_ars.values)
    return ds.assign_coords(
        slope_pct=("stop_ars", terrain["slope_pct"].to_numpy(dtype=float)),
        elev_m=("stop_ars", terrain["elev_m"].to_numpy(dtype=float)),
        slope_band=("stop_ars", slope_bands(terrain["slope_pct"].to_numpy(dtype=float))),
    )


def reconcile(ds: xr.Dataset, expected_total: int) -> int:
    """Same discipline as the BigQuery load: the array total must match the source."""
    total = int(ds.boardings.sum(skipna=True))
    if total != int(expected_total):
        raise ValueError(f"aggregate mismatch: xarray {total:,}, source {int(expected_total):,}")
    return total


def band_profile(ds: xr.Dataset) -> xr.DataArray:
    """
    Mean boardings per stop-hour by slope band, dims (hour, slope_band).

    Each stop-hour is first averaged over the months it was observed, then the stops
    of a band are averaged. Means rather than sums, because the bands hold very
    different numbers of stops and a sum would only restate how many steep stops exist.
    """
    per_stop_hour = ds.boardings.mean(dim="month", skipna=True)
    by_band = per_stop_hour.groupby("slope_band").mean(skipna=True)
    return by_band.sel(slope_band=BAND_LABELS).transpose("hour", "slope_band")


def band_shape(profile: xr.DataArray) -> xr.DataArray:
    """Each band's hourly profile as a share of its own daily total."""
    return profile / profile.sum(dim="hour")


def stops_per_band(ds: xr.Dataset) -> pd.Series:
    counts = pd.Series(ds.slope_band.values).value_counts()
    return counts.reindex(BAND_LABELS + [NO_TERRAIN], fill_value=0)


def readme_table(profile: xr.DataArray, hours=README_HOURS) -> str:
    """The README table: band means at chosen hours, and the steepest band against flat."""
    frame = profile.to_pandas()
    share = frame["12%+"] / frame["0-4%"]
    lowest = share.loc[list(hours)].idxmin()
    # Plain hyphens: a Windows console in cp949 cannot print an en dash (2026-11-09).
    lines = ["| Hour | 0-4% | 4-8% | 8-12% | 12%+ | 12%+ as share of 0-4% |",
             "| ---- | ---- | ---- | ----- | ---- | --------------------- |"]
    for hour in hours:
        row = frame.loc[hour]
        pct = f"{share.loc[hour]:.0%}"
        pct = f"**{pct}**" if hour == lowest else pct
        lines.append(f"| {hour:02d}   | " + " | ".join(f"{row[b]:.1f}" for b in BAND_LABELS)
                     + f" | {pct} |")
    return "\n".join(lines)


def load_demand(engine, months: list[str], attempts: int = 5, wait: int = 15,
                sleep=time.sleep) -> tuple[pd.DataFrame, int]:
    """
    Demand rows for each month and the source boarding total, one month at a time.

    Run from a workstation through the Cloud SQL proxy, the connection dropped part
    way twice (2026-11-09) while the database kept running. A dropped month is read
    again on a fresh connection; months already read are kept.
    """
    from sqlalchemy.exc import OperationalError

    frames, expected = [], 0
    for n, month in enumerate(months, 1):
        for attempt in range(1, attempts + 1):
            try:
                frame = pd.read_sql(DEMAND_SQL, engine, params={"use_ym": month})
                total = pd.read_sql(TOTAL_SQL, engine, params={"use_ym": month})["t"].iloc[0]
                break
            except OperationalError as error:
                if attempt == attempts:
                    raise
                first_line = str(error).strip().splitlines()[0]
                print(f"  {month}: connection lost ({first_line}); retry {attempt}/{attempts - 1} "
                      f"in {wait}s", flush=True)
                if engine is not None:
                    engine.dispose()
                sleep(wait)
        frames.append(frame)
        expected += int(total or 0)
        print(f"  {month}: {len(frame):,} rows ({n}/{len(months)})", flush=True)
    return pd.concat(frames, ignore_index=True), expected


def load_bigquery(project: str, marts: str = "transit_marts", staging: str = "transit_staging",
                  query=None) -> tuple[pd.DataFrame, pd.DataFrame, int]:
    """Demand, terrain and the source boarding total from the warehouse."""
    if query is None:
        from google.cloud import bigquery

        client = bigquery.Client(project=project)

        def query(sql):
            return client.query(sql).to_dataframe()

    names = {"project": project, "marts": marts, "staging": staging}
    demand = query(BQ_DEMAND_SQL.format(**names))
    slope = query(BQ_SLOPE_SQL.format(**names))
    expected = int(query(BQ_TOTAL_SQL.format(**names))["t"].iloc[0])
    demand["use_ym"] = demand["use_ym"].astype(str)
    for column in ("hour", "boardings", "alightings"):
        demand[column] = demand[column].astype("int64")
    slope["slope_pct"] = slope["slope_pct"].astype(float)
    slope["elev_m"] = slope["elev_m"].astype(float)
    return demand, slope, expected


def report(ds: xr.Dataset, source_rows: int) -> None:
    months = pd.to_datetime(ds.month.values)
    print(f"months {months.min():%Y-%m} to {months.max():%Y-%m}; "
          f"{source_rows:,} source rows -> grid {dict(ds.sizes)}")
    print("\nstops per slope band")
    print(stops_per_band(ds).to_string())

    hourly = ds.boardings.sum(dim=["stop_ars", "month"], skipna=True)
    print(f"\npeak hour: {int(hourly.idxmax()):02d}:00  ({int(hourly.max()):,} boardings)")

    profile = band_profile(ds)
    print("\nmean boardings per stop-hour by slope band")
    print(profile.to_pandas().round(1).to_string())

    shape = band_shape(profile)
    print("\npeak hour and share of the daily total, by slope band")
    for band in BAND_LABELS:
        row = shape.sel(slope_band=band)
        print(f"  {band:>6}  {int(row.idxmax()):02d}:00   share {float(row.max()):.3f}")
    print("  share at 18:00: " + ", ".join(
        f"{b} {float(shape.sel(slope_band=b, hour=18)):.3f}" for b in BAND_LABELS))

    print("\nREADME table")
    print(readme_table(profile))

    monthly = ds.boardings.sum(dim=["hour", "stop_ars"], skipna=True)
    print("\nmonthly totals and change")
    print(pd.DataFrame({
        "boardings": monthly.to_series().map(lambda v: f"{int(v):,}"),
        "change": monthly.diff(dim="month").to_series()
                         .reindex(monthly.month.values).map(
                             lambda v: "" if pd.isna(v) else f"{int(v):+,}"),
    }).to_string())


def main() -> None:
    from sqlalchemy import create_engine  # noqa: F401  (postgres source only)

    parser = argparse.ArgumentParser()
    parser.add_argument("--netcdf", default="demand.nc",
                        help="Where to write the array; empty to skip.")
    parser.add_argument("--source", choices=["postgres", "bigquery"], default="postgres")
    parser.add_argument("--bq-project", default=BQ_PROJECT)
    args = parser.parse_args()

    if args.source == "bigquery":
        print(f"reading from BigQuery ({args.bq_project})", flush=True)
        demand, slope, expected = load_bigquery(args.bq_project)
    else:
        engine = create_engine(db_url_from_env())
        months = pd.read_sql(MONTHS_SQL, engine)["use_ym"].astype(str).str.strip().tolist()
        print(f"loading {len(months)} months", flush=True)
        demand, expected = load_demand(engine, months)
        slope = pd.read_sql(SLOPE_SQL, engine)

    ds = build_dataset(demand, slope)
    total = reconcile(ds, expected)
    print(f"boardings reconciled: {total:,}")
    report(ds, len(demand))

    if args.netcdf:
        ds.to_netcdf(args.netcdf)
        print(f"\nwrote {args.netcdf}")


if __name__ == "__main__":
    main()
