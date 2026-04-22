import pandas as pd
import time
 
start = time.time()
 
# 1. 데이터 로드
print("[1/5] 데이터 로드 중...")
 
df_0916 = pd.read_csv(r'C:\data\seoul_od_data_20250916.csv', encoding='utf-8', dtype=str)
df_1014 = pd.read_csv(r'C:\data\노선별OD_20251014.csv', encoding='utf-8', dtype=str)
 
print(f"  0916: {len(df_0916):,}건 / 1014: {len(df_1014):,}건")
 
 
# 2. 컬럼명 정리
print("[2/5] 컬럼 정리 중...")
 
df_0916.columns = df_0916.columns.str.strip()
df_1014.columns = df_1014.columns.str.strip()
 
# 따옴표 및 공백 제거
for col in df_1014.columns:
    df_1014[col] = df_1014[col].str.replace('"', '', regex=False).str.strip()
 
for col in df_0916.columns:
    df_0916[col] = df_0916[col].str.strip()
 
# 순번 INT 변환 (조인 키로 사용)
df_0916['승차순번_INT'] = pd.to_numeric(df_0916['승차_정류장순번'], errors='coerce')
df_0916['하차순번_INT'] = pd.to_numeric(df_0916['하차_정류장순번'], errors='coerce')
df_1014['승차순번_INT'] = pd.to_numeric(df_1014['승차_정류장순번'], errors='coerce')
df_1014['하차순번_INT'] = pd.to_numeric(df_1014['하차_정류장순번'], errors='coerce')
 
 
# 3. 노선ID 매핑
print("[3/5] 노선ID 매핑 중...")
 
# 0916, 1014 각각 DISTINCT
df_0916_distinct = df_0916[['노선ID', '승차_정류장명', '승차순번_INT']].drop_duplicates()
df_1014_distinct = df_1014[['노선명', '승차_정류장명', '승차순번_INT']].drop_duplicates()
 
# 조인 후 일치 건수 집계
merged = df_1014_distinct.merge(df_0916_distinct, on=['승차_정류장명', '승차순번_INT'], how='inner')
 
route_count = (
    merged.groupby(['노선명', '노선ID'])
    .size()
    .reset_index(name='일치건수')
)
 
# 노선명당 일치건수 최대인 노선ID 1개 선택
route_match = (
    route_count
    .sort_values('일치건수', ascending=False)
    .drop_duplicates(subset='노선명', keep='first')[['노선명', '노선ID']]
)
 
print(f"  매핑된 노선 수: {len(route_match):,}개")
 
 
# 4. 표준코드 매핑
#    0916의 9자리 ARS = 표준코드
#    노선ID + 순번 기준으로 1014에 붙임
print("[4/5] 표준코드 매핑 중...")
 
# 승차 표준코드 (0916의 9자리 ARS를 표준코드로 사용)
boarding_map = (
    df_0916[['노선ID', '승차순번_INT', '승차_정류장ARS']]
    .drop_duplicates(subset=['노선ID', '승차순번_INT'])
    .rename(columns={'승차_정류장ARS': '승차_정류장표준코드'})
)
 
# 하차 표준코드 (0916의 9자리 ARS를 표준코드로 사용)
alighting_map = (
    df_0916[['노선ID', '하차순번_INT', '하차_정류장ARS']]
    .drop_duplicates(subset=['노선ID', '하차순번_INT'])
    .rename(columns={'하차_정류장ARS': '하차_정류장표준코드'})
)
 
# 1014에 노선ID 붙이기
df_result = df_1014.merge(route_match, on='노선명', how='left')
 
# 승차 표준코드 붙이기
df_result = df_result.merge(
    boarding_map,
    left_on=['노선ID', '승차순번_INT'],
    right_on=['노선ID', '승차순번_INT'],
    how='left'
)
 
# 하차 표준코드 붙이기
df_result = df_result.merge(
    alighting_map,
    left_on=['노선ID', '하차순번_INT'],
    right_on=['노선ID', '하차순번_INT'],
    how='left'
)
 
# ARS 코드 5자리 정규화
df_result['승차_정류장ARS'] = df_result['승차_정류장ARS'].str.zfill(5)
df_result['하차_정류장ARS'] = df_result['하차_정류장ARS'].str.zfill(5)
 
# NULL 보정: 같은 ARS 코드를 참고해서 표준코드 채우기
ars_boarding = (
    df_result[df_result['승차_정류장표준코드'].notna()]
    [['승차_정류장ARS', '승차_정류장표준코드']]
    .drop_duplicates(subset='승차_정류장ARS')
)
ars_alighting = (
    df_result[df_result['하차_정류장표준코드'].notna()]
    [['하차_정류장ARS', '하차_정류장표준코드']]
    .drop_duplicates(subset='하차_정류장ARS')
)
 
df_result = df_result.merge(ars_boarding, on='승차_정류장ARS', how='left', suffixes=('', '_보정'))
df_result['승차_정류장표준코드'] = df_result['승차_정류장표준코드'].fillna(df_result['승차_정류장표준코드_보정'])
df_result.drop(columns=['승차_정류장표준코드_보정'], inplace=True)
 
df_result = df_result.merge(ars_alighting, on='하차_정류장ARS', how='left', suffixes=('', '_보정'))
df_result['하차_정류장표준코드'] = df_result['하차_정류장표준코드'].fillna(df_result['하차_정류장표준코드_보정'])
df_result.drop(columns=['하차_정류장표준코드_보정'], inplace=True)

# NULL 보정 2차: ARS로 못 찾은 경우 정류장명 기준으로 보정
name_boarding = (
    df_result[df_result['승차_정류장표준코드'].notna()]
    [['승차_정류장명', '승차_정류장표준코드']]
    .drop_duplicates(subset='승차_정류장명')
)
name_alighting = (
    df_result[df_result['하차_정류장표준코드'].notna()]
    [['하차_정류장명', '하차_정류장표준코드']]
    .drop_duplicates(subset='하차_정류장명')
)
 
df_result = df_result.merge(name_boarding, on='승차_정류장명', how='left', suffixes=('', '_보정'))
df_result['승차_정류장표준코드'] = df_result['승차_정류장표준코드'].fillna(df_result['승차_정류장표준코드_보정'])
df_result.drop(columns=['승차_정류장표준코드_보정'], inplace=True)
 
df_result = df_result.merge(name_alighting, on='하차_정류장명', how='left', suffixes=('', '_보정'))
df_result['하차_정류장표준코드'] = df_result['하차_정류장표준코드'].fillna(df_result['하차_정류장표준코드_보정'])
df_result.drop(columns=['하차_정류장표준코드_보정'], inplace=True)


 
# 5. 결과 저장
print("[5/5] 결과 저장 중...")
 
df_final = df_result[[
    '기준일자', '노선명', '노선ID', '승차_정류장순번', '승차_정류장ARS', '승차_정류장표준코드', '승차_정류장명',
    '하차_정류장순번', '하차_정류장ARS', '하차_정류장표준코드', '하차_정류장명', '승객수'
]].rename(columns={'노선ID': '전환_노선ID'})
 
df_final.to_csv(r'C:\data\merged_od_data.csv', index=False, encoding='utf-8-sig', lineterminator='\n', sep='|')
# 줄바꿈 문자가 마지막 컬럼에 붙는 문제 수정
# 하차_정류장명에 쉼표가 포함된 값들이 있어서 구분자 |로 변경
df_final.to_excel(r'C:\data\merged_od_data.xlsx', index=False)
# 엑셀로도 저장
 
elapsed = time.time() - start
 
print(f"\n완료!")
print(f"  전체 행수:          {len(df_final):,}건")
print(f"  노선ID NULL:        {df_final['전환_노선ID'].isna().sum():,}건")
print(f"  승차 표준코드 NULL:  {df_final['승차_정류장표준코드'].isna().sum():,}건")
print(f"  하차 표준코드 NULL:  {df_final['하차_정류장표준코드'].isna().sum():,}건")
print(f"  소요 시간:          {elapsed:.1f}초")
