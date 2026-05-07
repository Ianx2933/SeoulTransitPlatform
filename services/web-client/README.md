# Seoul Transit Web Client - React Leaflet

This package contains a Vite React frontend for visualizing `/api/map/demand`.
(이 패키지는 `/api/map/demand`를 시각화하는 Vite React 프론트엔드이다.)

## Install

Run from `services/web-client`:

```bash
npm install
```

## Start Spring Boot First

Spring Boot must be running on:

```text
http://localhost:8080
```

## Start Frontend

```bash
npm run dev
```

Open:

```text
http://localhost:5173
```

## API Used

```http
GET /api/map/demand?mode=subway&dayType=mon&hour=8
```

## Notes

- Marker radius is calculated from `boarding + alighting`.
  (마커 반지름은 `boarding + alighting` 기준으로 계산한다.)
- Vite proxy forwards `/api` calls to Spring Boot.
  (Vite proxy가 `/api` 호출을 Spring Boot로 전달한다.)
