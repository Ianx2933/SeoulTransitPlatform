"""Opt-in disposable PostGIS test: RUN_TERRAIN_INTEGRATION=1 python -m pytest pipelines/terrain/tests."""
import importlib.util
import os
from pathlib import Path
import pytest

pytestmark=pytest.mark.integration
ROOT=Path(__file__).resolve().parents[1]

@pytest.fixture(scope='function')
def database():
    if os.environ.get('RUN_TERRAIN_INTEGRATION')!='1':
        pytest.skip('Set RUN_TERRAIN_INTEGRATION=1 to start disposable PostGIS')
    psycopg2=pytest.importorskip('psycopg2')
    from testcontainers.postgres import PostgresContainer
    with PostgresContainer('postgis/postgis:16-3.4', dbname='terrain_test') as container:
        url=container.get_connection_url().replace('postgresql+psycopg2://','postgresql://',1)
        with psycopg2.connect(url) as conn:
            with conn.cursor() as cur:
                cur.execute((ROOT.parents[1]/'database/terrain/sql/01_terrain_schema.sql').read_text(encoding='utf-8'))
                cur.execute('''CREATE TABLE public.admin_dong_boundary (
                    base_date varchar,adm_cd varchar,adm_nm varchar,geom geometry(MultiPolygon,4326))''')
                cur.execute('''INSERT INTO public.admin_dong_boundary VALUES
                    ('20260101','A','West',ST_Multi(ST_GeomFromText('POLYGON((0 0,1 0,1 1,0 1,0 0))',4326))),
                    ('20260101','B','East',ST_Multi(ST_GeomFromText('POLYGON((1 0,2 0,2 1,1 1,1 0))',4326))),
                    ('20250101','OLD','Old',ST_Multi(ST_GeomFromText('POLYGON((0 0,2 0,2 1,0 1,0 0))',4326)))''')
                cur.execute('''CREATE TABLE public.bus_stop_location (
                    "노드id" text PRIMARY KEY,"정류장번호" text,"정류장명" text,
                    geom geometry(Point,4326),elev_m float8,slope_pct float8)''')
                cur.execute('''INSERT INTO public.bus_stop_location VALUES
                    ('001','001','Master',ST_SetSRID(ST_MakePoint(0.5,0.5),4326),10,8)''')
        yield url

def module():
    spec=importlib.util.spec_from_file_location('publish_terrain',ROOT/'publish_terrain.py')
    p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p);return p

def data():
    return [dict(node_id=id,stop_no=id,stop_name=id,longitude=lon,latitude=lat,elev_m=elev,slope_pct=slope)
            for id,lon,lat,elev,slope in [('001',0.5,0.5,10,7.99),('002',1,0.5,None,8),
                                          ('003',1.5,0.5,20,8.01),('004',3,3,None,None)]]

def publish(conn,id,rows,**kwargs):
    return module().publish(conn,dataset_id=id,source_id='fixture',slope_method='test',
        boundary_date='20260101',rows=rows,**kwargs)

def test_atomic_publication_boundary_ties_and_master_preservation(database):
    import psycopg2
    with psycopg2.connect(database) as conn:
        report=publish(conn,'v1',data(),activate=True,expected_stops=4,expected_at_least=2)
        assert report['assigned_stop_count']==3
        assert report['ambiguous_boundary_stop_count']==1
        with conn.cursor() as cur:
            cur.execute("SELECT adm_cd,assignment_method,candidate_count FROM public.terrain_stop_dong_assignment WHERE dataset_id='v1' AND node_id='002'")
            assert cur.fetchone()==('A','boundary',2)
            cur.execute("SELECT COUNT(*),COUNT(DISTINCT node_id) FROM public.terrain_stop_dong_assignment WHERE dataset_id='v1'")
            assert cur.fetchone()==(4,4)
            cur.execute('SELECT COUNT(*),MAX(slope_pct) FROM public.bus_stop_location')
            assert cur.fetchone()==(1,8.0)
        conn.commit()
        with pytest.raises(Exception): publish(conn,'broken',data(),activate=True,expected_stops=5)
        with conn.cursor() as cur:
            cur.execute('SELECT dataset_id FROM public.terrain_active_dataset')
            assert cur.fetchone()[0]=='v1'
            cur.execute("SELECT COUNT(*) FROM public.terrain_dataset WHERE dataset_id='broken'")
            assert cur.fetchone()[0]==0
        conn.commit()
        dry=publish(conn,'dry',data(),dry_run=True)
        assert dry['dry_run']
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM public.terrain_dataset WHERE dataset_id='dry'")
            assert cur.fetchone()[0]==0
        conn.commit()
        with conn.cursor() as cur:
            cur.execute('TRUNCATE public.bus_stop_location')
            cur.execute("SELECT COUNT(*) FROM public.bus_stop_terrain_stats WHERE dataset_id='v1'")
            assert cur.fetchone()[0]==4
        conn.commit()
        rows=data();rows[0]['slope_pct']=12
        publish(conn,'v2',rows,activate=True)
        with conn.cursor() as cur:
            cur.execute("SELECT dataset_id FROM public.terrain_active_dataset")
            assert cur.fetchone()[0]=='v2'
            cur.execute("SELECT slope_pct FROM public.bus_stop_terrain_stats WHERE dataset_id='v1' AND node_id='001'")
            assert cur.fetchone()[0]==7.99
            with pytest.raises(psycopg2.Error):
                cur.execute("UPDATE public.bus_stop_terrain_stats SET slope_pct=0 WHERE dataset_id='v1'")
        conn.rollback()

def test_overlapping_interiors_reject_without_activation(database):
    import psycopg2
    with psycopg2.connect(database) as conn:
        with conn.cursor() as cur:
            cur.execute("INSERT INTO public.admin_dong_boundary VALUES ('20260101','C','Overlap',ST_Multi(ST_GeomFromText('POLYGON((0.25 0.25,0.75 0.25,0.75 0.75,0.25 0.75,0.25 0.25))',4326)))")
        conn.commit()
        with pytest.raises(module().PublishError,match='multiple administrative polygons'):
            publish(conn,'overlap',data(),activate=True)
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM public.terrain_dataset WHERE dataset_id='overlap'")
            assert cur.fetchone()[0]==0
            cur.execute('SELECT COUNT(*) FROM public.terrain_active_dataset')
            assert cur.fetchone()[0]==0
