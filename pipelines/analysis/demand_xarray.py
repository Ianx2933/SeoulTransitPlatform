"""Load hourly boarding demand into a labelled 3-D array and profile it by terrain.

The source is already three-dimensional -- (month, hour, stop) -- so it belongs in
xarray rather than in a flat frame. Slope becomes a non-dimension coordinate on the
stop axis, which is what lets a terrain grouping be one call instead of a pivot.

    set PGPASSWORD=...
    python demand_xarray.py
"""

import os
import sys

import pandas as pd
import xarray as xr
from sqlalchemy import create_engine

PWD = os.environ.get("PGPASSWORD")
if not PWD:
    sys.exit("PGPASSWORD is not set.  Run:  $env:PGPASSWORD='...'")

engine = create_engine(
    f"postgresql+psycopg2://{os.environ.get('PGUSER','postgres')}:{PWD}"
    f"@{os.environ.get('PGHOST','localhost')}:{os.environ.get('PGPORT','5432')}"
    f"/{os.environ.get('PGDATABASE','Seoul_Transit')}"
)

# Aggregate away route_no in SQL. The analysis is per stop, and pulling 5.58M rows
# to sum them in memory would be the wrong side of the pipeline to do it on.
DEMAND_SQL = """
SELECT use_ym,
       hour,
       stop_ars,
       sum(boarding_passengers)::bigint  AS boardings,
       sum(alighting_passengers)::bigint AS alightings
FROM hourly_bus_stop_passenger
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


def load() -> xr.Dataset:
    df = pd.read_sql(DEMAND_SQL, engine)
    print(f"rows from Postgres: {len(df):,}")

    df["month"] = pd.to_datetime(df.use_ym + "01", format="%Y%m%d")
    df = df.drop(columns="use_ym")

    # to_xarray() builds the full (month, hour, stop) grid. Combinations absent from
    # the source become NaN, which is correct: a stop with no service at 03:00 is a
    # missing observation, not a zero. Anything that sums over it must use skipna.
    ds = df.set_index(["month", "hour", "stop_ars"]).to_xarray()
    print(f"grid: {dict(ds.sizes)}")

    slope = pd.read_sql(SLOPE_SQL, engine).drop_duplicates("stop_ars").set_index("stop_ars")
    aligned = slope.reindex(ds.stop_ars.values)
    ds = ds.assign_coords(
        slope_pct=("stop_ars", aligned.slope_pct.values),
        elev_m=("stop_ars", aligned.elev_m.values),
    )
    matched = int(aligned.slope_pct.notna().sum())
    print(f"stops with terrain: {matched:,} of {ds.sizes['stop_ars']:,}")
    return ds


def reconcile(ds: xr.Dataset) -> None:
    """Same discipline as the BigQuery load: totals must match the source."""
    total = int(ds.boardings.sum(skipna=True))
    expected = pd.read_sql(
        "SELECT sum(boarding_passengers)::bigint AS t FROM hourly_bus_stop_passenger",
        engine,
    ).t.iloc[0]
    print(f"\nboardings  xarray {total:,}  postgres {int(expected):,}")
    assert total == int(expected), "aggregate mismatch"
    print("reconciled")


def profiles(ds: xr.Dataset) -> None:
    # Hourly profile across all stops.
    hourly = ds.boardings.sum(dim=["stop_ars", "month"], skipna=True)
    peak = int(hourly.idxmax())
    print(f"\npeak hour: {peak:02d}:00  ({int(hourly.max()):,} boardings)")

    # Slope bands as a coordinate, so groupby does the pivot.
    bands = xr.DataArray(
        pd.cut(
            ds.slope_pct.values,
            bins=[0, 4, 8, 12, 100],
            labels=["0-4%", "4-8%", "8-12%", "12%+"],
        ),
        dims="stop_ars",
        name="slope_band",
    )
    ds = ds.assign_coords(slope_band=bands)

    # Mean boardings per stop-hour, by terrain band. Comparing means rather than
    # sums matters here: the bands hold very different numbers of stops, so a sum
    # would only restate how many steep stops exist.
    by_band = ds.boardings.mean(dim="month", skipna=True).groupby("slope_band").mean(skipna=True)
    print("\nmean boardings per stop-hour by slope band")
    print(by_band.to_series().unstack("slope_band").round(1).to_string())

    # Normalised hourly shape per band -- does demand peak at a different hour on
    # steep ground? Normalising removes the level difference so the shapes compare.
    shape = (
        ds.boardings.groupby("slope_band").mean(skipna=True)
        if "hour" not in ds.boardings.dims
        else ds.boardings.mean(dim="month", skipna=True)
        .groupby("slope_band")
        .mean(skipna=True)
    )
    if "hour" in shape.dims:
        norm = shape / shape.sum(dim="hour")
        print("\npeak hour by slope band")
        for b in norm.slope_band.values:
            row = norm.sel(slope_band=b)
            print(f"  {b:>6}  {int(row.idxmax()):02d}:00   share {float(row.max()):.3f}")

    # Month-over-month change, one call.
    monthly = ds.boardings.sum(dim=["hour", "stop_ars"], skipna=True)
    print("\nmonthly totals and change")
    print(monthly.to_series().apply(lambda v: f"{int(v):,}").to_string())
    print(monthly.diff(dim="month").to_series().apply(lambda v: f"{int(v):+,}").to_string())


if __name__ == "__main__":
    ds = load()
    reconcile(ds)
    profiles(ds)

    ds.to_netcdf("demand.nc")
    print("\nwrote demand.nc")
