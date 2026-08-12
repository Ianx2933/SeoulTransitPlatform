# Cache Profiles

This project uses Redis as the default cache provider and Caffeine as a lightweight local profile.
All paths are relative to the repository root.

## Profile matrix

| Profile | Cache type | Purpose |
|---|---|---|
| default | Redis | Deployment-like local execution and future GCP parity |
| local-simple | Caffeine | Lightweight local development without Redis |
| test | Testcontainers Redis/PostgreSQL | Automated context and integration smoke tests |

## Default Redis mode

Default configuration expects Redis at:

```text
localhost:6379
```

Required local environment variables:

```powershell
$env:CACHE_TYPE='redis'
$env:REDIS_HOST='localhost'
$env:REDIS_PORT='6379'
```

bash:

```bash
export CACHE_TYPE='redis'
export REDIS_HOST='localhost'
export REDIS_PORT='6379'
```

Start Redis from the repository root:

```bash
docker compose up -d redis
docker exec -it seoul-transit-redis redis-cli ping
```

Expected:

```text
PONG
```

## Caffeine local-simple mode

Use this when Redis is not needed for a quick local API check.

```powershell
cd services\api-server

$env:SPRING_PROFILES_ACTIVE='local-simple'
$env:DB_PASSWORD='your-local-postgres-password'
$env:ADMIN_API_TOKEN='local-dev-token'
$env:JPA_DDL_AUTO='validate'

mvn spring-boot:run
```

The profile is defined in
`services/api-server/src/main/resources/application-local-simple.yaml`:

```yaml
spring:
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=1000,expireAfterWrite=10m
```

## Why Redis remains default

Redis remains the default because it better matches future deployment
behavior:

- external cache process;
- cross-instance cache compatibility;
- closer to Memorystore-style deployment;
- avoids designing only for single-process local behavior.

Caffeine is retained for developer convenience. Choosing Caffeine as the
default would hide cache-serialization problems until the first deployment,
which is exactly the class of problem Phase 6.10 exists to surface early.

## Health implications

When Redis is the active cache provider, `/actuator/health` reports `DOWN` if
Redis is not running.

Typical failure:

```text
RedisConnectionFailureException: Unable to connect to Redis
Connection refused: localhost/127.0.0.1:6379
```

Fix, from the repository root:

```bash
docker compose up -d redis
```

Or switch to `local-simple`, where health does not depend on Redis.

This matters for a first-time reader: running `mvn spring-boot:run` with no
arguments and no Redis produces a `DOWN` health check, which looks like a
broken project rather than a missing dependency.

## Testcontainers

The test context does not require a manually running local Redis instance.
Testcontainers starts Redis and PostgreSQL for test scope.

Configuration:

```text
services/api-server/src/test/java/com/ian/transit/TestcontainersConfiguration.java
```

Redis is a `GenericContainer`, so it needs an explicit service-connection name
for Spring Boot to derive connection details:

```java
@Bean
@ServiceConnection(name = "redis")
GenericContainer<?> redisContainer() { ... }
```

`PostgreSQLContainer` needs no name because Spring Boot recognizes the type.

Test classes belong under:

```text
services/api-server/src/test/java/com/ian/transit/TransitApplicationTests.java
```

Do not place test classes under:

```text
services/api-server/src/main/java
```

Main source code cannot access test-scope dependencies such as JUnit and
Spring Boot test support.

Running tests requires a working Docker daemon.