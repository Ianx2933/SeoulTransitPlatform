# Congestion Phase 5 Module

Spring Boot Phase 5 Congestion module for SeoulTransitPlatform.

## Package

`com.ian.transit.congestion`

## Structure

```text
congestion/
├── api/
│   └── CongestionController.java
├── application/
│   ├── CongestionQueryService.java
│   └── OccupancyCalculator.java
├── infrastructure/
│   └── CongestionQueryRepository.java
├── dto/
│   ├── CongestionResponse.java
│   └── SectionCongestionResponse.java
└── model/
    ├── CongestionLevel.java
    └── CongestionRow.java
```

## Endpoints

```http
GET /api/congestion/{routeName}?date=20251111
GET /api/congestion?routes=143,401,N13&date=20251111
GET /api/congestion/stops?route=영등포10&date=20251111
GET /api/congestion/section?route=영등포10&from=영등포역&to=당산역&date=20251111
```

## Notes

- Repository owns SQL only.
- Service owns orchestration and section filtering.
- Calculator owns congestion percentage, level, and color conversion.
- DTOs use Java 21 records.
- SQL assumes PostgreSQL table names:
  - `analysis_table_final`
  - `bus_stop_location`
