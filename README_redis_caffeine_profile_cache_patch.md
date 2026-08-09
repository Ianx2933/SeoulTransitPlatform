# Redis/Caffeine Profile Cache Patch

This patch aligns cache configuration with the GCP-oriented deployment path:
Redis is the default cache provider, and Caffeine is available through the
`local-simple` profile for lightweight local runs.

## Included files

```text
services/api-server/pom.xml
services/api-server/src/main/resources/application.yaml
services/api-server/src/main/resources/application-local-simple.yaml
services/api-server/src/main/java/com/ian/transit/common/config/CacheConfig.java
services/api-server/src/test/java/com/ian/transit/TestcontainersConfiguration.java
README_security_hygiene_patch.md
.env.example
docker-compose.yaml
```

## What changed

1. Redis starter restored.
2. Caffeine dependency retained for `local-simple`
3. Default cache provider changed to Redis.
4. `application-local-simple.yaml` added for Caffeine local mode.
5. `TestcontainersConfiguration` added with `@ServiceConnection(name = "redis")`.
6. `.env.example` sanitized and Redis variables added.
7. Minimal local `docker-compose.yaml` with Redis added.

## Verification

```cmd
cd C:\Users\miyum\SeoulTransitPlatform

REM Start Redis for normal local runs.
docker compose up -d redis

cd services\api-server
set DB_PASSWORD=your-db-password
set ADMIN_API_TOKEN=local-dev-token
set JPA_DDL_AUTO=validate

mvn -DskipTests compile
mvn clean test
```

For lightweight local mode without Redis:

```cmd
cd C:\Users\miyum\SeoulTransitPlatform\services\api-server
set SPRING_PROFILES_ACTIVE=local-simple
set DB_PASSWORD=your-db-password
set ADMIN_API_TOKEN=local-dev-token
mvn spring-boot:run
```
