# Map Demand API Package

This package contains Spring Boot files for `/api/map/demand`.

## Included Files

```text
services/api-server/src/main/java/com/seoultransit/api/map/
├─ controller/MapDemandController.java
├─ service/MapDemandService.java
├─ repository/MapDemandRepository.java
└─ dto/MapDemandResponse.java

services/api-server/src/main/resources/sql/
├─ subway_station_join_alias.sql
└─ verify_subway_join.sql
```

## Package Name

The Java package is currently:

```java
package com.seoultransit.api.map...
```

Change this package name to match your existing Spring project package.

## API

```http
GET /api/map/demand?mode=subway&dayType=mon&hour=8
```

## Bus Query Warning

`MapDemandRepository` assumes this bus coordinate table:

```sql
bus_stop_location(node_id, lat, lng)
```

If your actual bus stop coordinate table has a different name or column structure,
edit `findBusMapDemand()`.
