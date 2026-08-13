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

df = pd.read_csv('<DATA_DIR>/20251014_버스OD.csv', dtype=str, encoding='cp949')

# 승차 표준코드 보정 (ARS + 정류장명 기준)
승차매핑 = df.dropna(subset=['승차_정류장표준코드'])\
             .drop_duplicates(subset=['승차_정류장ARS', '승차_정류장명'])\
             .set_index(['승차_정류장ARS', '승차_정류장명'])['승차_정류장표준코드']

df['승차_정류장표준코드'] = df.apply(
    lambda r: 승차매핑.get((r['승차_정류장ARS'], r['승차_정류장명']), r['승차_정류장표준코드'])
    if pd.isna(r['승차_정류장표준코드']) else r['승차_정류장표준코드'],
    axis=1
)

# 하차도 동일하게
하차매핑 = df.dropna(subset=['하차_정류장표준코드'])\
             .drop_duplicates(subset=['하차_정류장ARS', '하차_정류장명'])\
             .set_index(['하차_정류장ARS', '하차_정류장명'])['하차_정류장표준코드']

df['하차_정류장표준코드'] = df.apply(
    lambda r: 하차매핑.get((r['하차_정류장ARS'], r['하차_정류장명']), r['하차_정류장표준코드'])
    if pd.isna(r['하차_정류장표준코드']) else r['하차_정류장표준코드'],
    axis=1
)

# 잔여 NULL 확인
print("승차 NULL 잔여:", df['승차_정류장표준코드'].isna().sum())
print("하차 NULL 잔여:", df['하차_정류장표준코드'].isna().sum())

# 저장
df.to_csv('<DATA_DIR>/20251014_버스OD_보정.csv', index=False, encoding='utf-8-sig')

null_승차 = df[df['승차_정류장표준코드'].isna()][['승차_정류장ARS', '승차_정류장명', '승차_정류장순번']].drop_duplicates()
print(null_승차)
print("총", len(null_승차), "개 정류장")

# 35277 하나만 DB에서 확인된 것 수동 보정
df.loc[(df['승차_정류장ARS'] == '35277') & (df['승차_정류장표준코드'].isna()), '승차_정류장표준코드'] = '218000198'

# 나머지 26건 제외
df = df.dropna(subset=['승차_정류장표준코드'])

print("최종 NULL 확인:")
print("승차 NULL 잔여:", df['승차_정류장표준코드'].isna().sum())
print("하차 NULL 잔여:", df['하차_정류장표준코드'].isna().sum())
print("총 데이터 건수:", len(df))

df.to_csv('<DATA_DIR>/20251014_버스OD_보정.csv', index=False, encoding='utf-8-sig', sep='|')
print("저장 완료!")