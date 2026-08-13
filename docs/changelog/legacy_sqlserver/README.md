# Legacy — SQL Server Era

Artefacts from before the migration to PostgreSQL/PostGIS.
(PostgreSQL/PostGIS 이전 시절의 산출물입니다.)

Nothing here runs against the current system. T-SQL syntax is not PostgreSQL
syntax, and several of the tables referenced no longer exist. These files are
kept because they are the evidence behind decisions that still hold.

(현재 시스템에서 동작하지 않습니다. 지금도 유효한 판단들의 근거 자료로
보관합니다.)

## Contents

| File | What it is | Why it is kept |
|---|---|---|
| `troubleshooting.md` | 14 problems encountered and resolved | Several shaped the current architecture |
| `create_analysis_table_final.sql` | Original DDL for the curated OD table | Documents why the current column types are what they are |
| `analysis_queries.sql` | Analysis query collection | Produced the findings in `docs/analysis/policy_insights.md` |
| `null_standard_code_fix.py` | Standard-code NULL correction | First version of what became the four-stage fallback |
| `restore_mapping.py` | Bulk reload into SQL Server | Records the original load procedure |

## Decisions from this era that still hold

**Numeric column types on `analysis_table_final`.** The original DDL declared
every column VARCHAR. Four were later converted to INT and BIGINT because the
implicit CASTs were disabling indexes. The current schema keeps those numeric
types, and the JPA entity depends on them. Reading
`create_analysis_table_final.sql` alone would suggest the opposite, so the
conversion is recorded at the top of that file.

**Composite matching with recorded confidence.** ARS codes are not unique
across Seoul, Gyeonggi, and Incheon, and standard codes were sometimes
mis-entered. The resolution — join on ARS plus stop name, bounded by
coordinates — is why `integrated_bus_stop_location` carries `match_method` and
`match_confidence` today. See item 3 in `troubleshooting.md`.

**Zero-padded identifiers.** Storing ARS as an integer drops the leading zero,
so identifiers are kept as text and padded at join time with
`LPAD(node_id, 5, '0')`. That padded comparison is not sargable against a raw
column, which is why Phase 6.9-2 needed expression indexes rather than plain
btree ones. See item 8.

**JdbcTemplate over JPA.** Hibernate 7.x rejected window functions in JPQL, so
queries moved to native SQL through `JdbcTemplate`. Only one table in the
current codebase has an `@Entity` as a result, which is why `ddl-auto: validate`
guards exactly one table at startup. See item 13.

**Virtual stop filtering.** Depot and temporary stops carry ARS `00000` and
have no coordinates. The current smoke tests still assert that `00000` does not
appear in map results. See item 6.

## Scrubbing

Local paths and the SQL Server instance name have been replaced with
placeholders (`<DATA_DIR>`, `<SQL_SERVER_INSTANCE>`) in the Python files. The
database password that appeared in the original scripts has been removed.

(로컬 경로와 인스턴스명은 플레이스홀더로 교체했고, 원본 스크립트에 있던
데이터베이스 비밀번호는 제거했습니다.)
