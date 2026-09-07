import importlib.util
import math
from pathlib import Path
import pytest

MODULE = Path(__file__).resolve().parents[1] / 'publish_terrain.py'
spec = importlib.util.spec_from_file_location('publish_terrain', MODULE)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)

def row(**changes):
    result=dict(node_id='00001',stop_no='001',stop_name='Stop',longitude='127.0',
                latitude='37.0',elev_m='15',slope_pct='8')
    result.update(changes)
    return result

@pytest.mark.parametrize('value', ['NaN','Infinity','-Infinity','abc'])
def test_rejects_nonfinite_or_nonnumeric(value):
    with pytest.raises(p.PublishError): p.normalize_row(row(slope_pct=value))

@pytest.mark.parametrize('value', ['-0.01','-9998'])
def test_rejects_negative_grades(value):
    with pytest.raises(p.PublishError): p.normalize_row(row(slope_pct=value))

def test_preserves_identifiers_and_accepts_steep_grades():
    result=p.normalize_row(row(slope_pct='125.5'))
    assert result[0]=='00001' and result[-1]==125.5
    with pytest.raises(p.PublishError): p.normalize_row(row(node_id=' 00001 '))

def test_nodata_is_missing_not_zero():
    result=p.normalize_row(row(elev_m='-9999',slope_pct='-9999'))
    assert result[-2:]==(None,None)
    assert p.normalize_row(row(elev_m='0',slope_pct='0'))[-2:]==(0.0,0.0)

def test_coordinates_and_required_fields():
    for change in ({'longitude':'181'},{'latitude':'-91'},{'node_id':''},{'stop_name':''}):
        with pytest.raises(p.PublishError): p.normalize_row(row(**change))

def test_csv_column_mapping_and_leading_zeros(tmp_path):
    f=tmp_path/'stops.csv'
    f.write_text('id,name,no,lon,lat,elevation,grade\n00001,Stop,001,127,37,10,8\n',encoding='utf-8')
    mapping=dict(node_id='id',stop_name='name',stop_no='no',longitude='lon',latitude='lat',elev_m='elevation',slope_pct='grade')
    assert list(p.csv_rows(f,mapping))[0]['node_id']=='00001'
    with pytest.raises(p.PublishError): list(p.csv_rows(f))

def test_invalid_source_date_and_version_rejected_before_database_use():
    class UnusedConnection: pass
    for date in ('20260230','2026-01-01'):
        with pytest.raises(p.PublishError): p.publish(UnusedConnection(),dataset_id='v1',source_id='GLO-30',slope_method='test',boundary_date=date,rows=[])
    with pytest.raises(p.PublishError): p.publish(UnusedConnection(),dataset_id='',source_id='GLO-30',slope_method='test',boundary_date='20260101',rows=[])
