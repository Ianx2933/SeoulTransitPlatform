# Phase 5.5 Operational Quality

This package adds operational quality improvements before moving to Phase 6 Prediction.

## Included

```text
common/exception/
├── ErrorResponse.java
├── GlobalExceptionHandler.java
└── NotFoundException.java

congestion/application/
└── CongestionQueryService.java
```

## What Changed

- Blank request parameters return `400 Bad Request`.
- Missing route/date data returns `404 Not Found`.
- Unexpected exceptions return a consistent `500 Internal Server Error` JSON body.
- CongestionQueryService now throws explicit exceptions instead of silently returning empty lists.

## Example 404 Response

```json
{
  "timestamp": "2026-04-24T22:30:00",
  "status": 404,
  "error": "Not Found",
  "message": "No congestion data found for routeName=999, date=20251111",
  "path": "/api/congestion/999"
}
```

## Where to Place

Copy into:

```text
services/api-server/src/main/java/com/ian/transit/
```

Make sure your main class is under:

```text
com.ian.transit
```

so that `common.exception` and `congestion` are both component-scanned.
