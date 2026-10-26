# Deployment configuration and cache profiles

> Moved from the project README.

## Environment variables added for deployment

| Variable | Default | Why it exists |
|---|---|---|
| `PORT` | 8080 | Cloud Run assigns the port; `server.port` must read it rather than bind a fixed one |
| `DB_POOL_MAX` | 5 | Hikari's default of 10, multiplied by Cloud Run instances, exceeds what `db-f1-micro` accepts |
| `REDIS_HEALTH_ENABLED` | true | Actuator registers a Redis health check from the starter being on the classpath, not from the active cache provider. Where no Redis exists the check fails permanently and `/actuator/health` answers 503 |
| `PREDICTION_SERVICE_AUTH_ENABLED` | false | Attach an ID token to prediction calls |

## Cache profiles

| Profile | Cache | Intended use |
|---|---|---|
| default | Redis | Local Redis and deployment-like execution |
| local-simple | Caffeine | Lightweight local execution without Redis |
| test | Testcontainers Redis/PostGIS | Automated tests |

Run the API without Redis:

```powershell
cd services\api-server

$env:SPRING_PROFILES_ACTIVE='local-simple'
$env:DB_PASSWORD='your-local-postgres-password'
$env:ADMIN_API_TOKEN='local-dev-token'
$env:JPA_DDL_AUTO='validate'

mvn spring-boot:run
```

With the default profile and no Redis running, `/actuator/health` reports
`DOWN`. That is a missing dependency, not a broken build — see
[`docs/architecture/cache_profiles.md`](docs/architecture/cache_profiles.md).

Switching `CACHE_TYPE` alone does not silence it. Actuator registers the Redis
health check because `spring-boot-starter-data-redis` is on the classpath, not
because Redis is the active cache, so an environment with no Redis at all needs
`REDIS_HEALTH_ENABLED=false` as well. The deployed service sets both.

The prediction-service ID-token path is disabled locally (`PREDICTION_SERVICE_AUTH_ENABLED=false`) because no application default credentials exist there.
