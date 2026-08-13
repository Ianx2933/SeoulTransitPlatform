"""
LEGACY - SQL Server era. Not used by the current pipeline.
(레거시 - SQL Server 시절 스크립트. 현재 파이프라인에서 사용하지 않습니다.)

Kept as a record of how the curated OD table was originally built. It targets
SQL Server through pyodbc and a table structure that no longer exists; the
current equivalent is pipelines/od_correction/ against PostgreSQL.

Local paths and the connection string have been replaced with placeholders.
(로컬 경로와 연결 문자열은 플레이스홀더로 교체했습니다.)
"""

import pandas as pd
from sqlalchemy import create_engine, text

engine = create_engine('mssql+pyodbc://<SQL_SERVER_INSTANCE>/Seoul_Transit?driver=ODBC+Driver+17+for+SQL+Server&trusted_connection=yes')

cols = ['기준일자', '노선명', '전환_노선ID', 
        '승차_정류장순번', '승차_정류장ARS', '승차_정류장표준코드', '승차_정류장명',
        '하차_정류장순번', '하차_정류장ARS', '하차_정류장표준코드', '하차_정류장명',
        '승객수']

df = pd.read_csv(r'<DATA_DIR>\Final_20251014.csv', 
                 dtype=str, encoding='utf-8-sig', header=None, names=cols)

# DB 전체 삭제 후 재적재
with engine.connect() as conn:
    conn.execute(text("DELETE FROM Seoul_Transit.dbo.Analysis_Table_Final"))
    conn.commit()
    print("기존 데이터 삭제 완료")

df.to_sql('Analysis_Table_Final', engine, schema='dbo', 
          if_exists='append', index=False, chunksize=10000)
print(f"재적재 완료: {len(df)}건")