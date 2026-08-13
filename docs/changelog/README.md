# Changelog

Point-in-time records: what changed, and what came before.

These files are frozen once written. They are not updated when later phases
change the same area. For how the system currently behaves, read the reference
documentation instead:

| Question | Read |
|---|---|
| How is the system designed? | [`../architecture/overview.md`](../architecture/overview.md) |
| How do the map endpoints work? | [`../architecture/map_demand_api.md`](../architecture/map_demand_api.md) |
| How do cache profiles work? | [`../architecture/cache_profiles.md`](../architecture/cache_profiles.md) |
| How do I create the database? | [`../deployment/database_setup.md`](../deployment/database_setup.md) |
| How do I run it locally? | [`../deployment/local_runbook.md`](../deployment/local_runbook.md) |
| How do I verify it works? | [`../deployment/smoke_tests.md`](../deployment/smoke_tests.md) |
| What was found with it? | [`../analysis/policy_insights.md`](../analysis/policy_insights.md) |

Where a changelog entry and a reference document disagree, the reference
document is correct.

## Patch records

| File | Records |
|---|---|
| `README_redis_caffeine_profile_cache_patch.md` | Redis default / Caffeine `local-simple` split, Testcontainers setup |
| `README_security_hygiene_patch.md` | Secret handling and `.env.example` sanitisation |
| `README_phase_6_9_district_demand_patch.md` | Phase 6.9 district demand feature |
| `README_phase6_prediction_dayofweek_refactor.md` | `dayType` replaced by `dayOfWeek` + `isHoliday` in prediction features |

## Predecessor project

The platform began as `Transit-Data_Seoul`, running on SQL Server Express with
Folium for visualisation. Both were replaced, but the problems solved there
shaped decisions that still hold.

| Directory | Contents |
|---|---|
| [`legacy_sqlserver/`](./legacy_sqlserver/) | 14 troubleshooting records, original DDL, analysis queries, correction scripts |
| [`legacy_folium/`](./legacy_folium/) | Map generation script and the tool-selection history |

Nothing in those directories runs against the current system.
(해당 디렉터리의 코드는 현재 시스템에서 동작하지 않습니다.)
