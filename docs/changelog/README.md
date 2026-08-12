# Changelog

This folder holds per-patch change records moved out of the repository root
during Phase 6.10.

These files describe **what changed at a point in time**. They are frozen once
written and are not updated when later phases change the same area.

For **how the system currently behaves**, read the reference documentation
instead:

| Question | Read |
|---|---|
| How do cache profiles work now? | [`../architecture/cache_profiles.md`](../architecture/cache_profiles.md) |
| How is the system designed? | [`../architecture/overview.md`](../architecture/overview.md) |
| How do I create the database? | [`../deployment/database_setup.md`](../deployment/database_setup.md) |
| How do I run it locally? | [`../deployment/local_runbook.md`](../deployment/local_runbook.md) |
| How do I verify it works? | [`../deployment/smoke_tests.md`](../deployment/smoke_tests.md) |

Where a changelog entry and a reference document disagree, the reference
document is correct.

## Contents

| File | Records |
|---|---|
| `README_redis_caffeine_profile_cache_patch.md` | Redis default / Caffeine `local-simple` split, Testcontainers setup |
| `README_security_hygiene_patch.md` | Secret handling and `.env.example` sanitization |
| `README_phase_6_9_district_demand_patch.md` | Phase 6.9 district demand feature |
