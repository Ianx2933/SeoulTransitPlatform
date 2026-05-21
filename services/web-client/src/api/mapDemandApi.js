/**
 * Calls the Spring Boot map demand API.
 * (Spring Boot 지도 수요 API를 호출합니다.)
 *
 * Vite proxy sends `/api/...` requests to `http://localhost:8080`.
 * (Vite proxy는 `/api/...` 요청을 `http://localhost:8080`으로 전달합니다.)
 */
export async function fetchMapDemand({
  mode,
  lines,
  dayType,
  dayTypes,
  dayAggregation,
  hours
}) {
  const params = new URLSearchParams({
    mode
  });

  /**
   * Multiple day types are sent as comma-separated strings.
   * (다중 요일 유형은 쉼표로 구분된 문자열로 전송합니다.)
   *
   * The legacy single dayType parameter is still supported by the backend.
   * (기존 단일 dayType 파라미터도 백엔드에서 계속 지원합니다.)
   */
  if (Array.isArray(dayTypes) && dayTypes.length > 0) {
    params.append("dayTypes", dayTypes.join(","));
  } else if (dayType) {
    params.append("dayType", dayType);
  }

  /**
   * dayAggregation controls whether selected day types are summed or averaged.
   * (dayAggregation은 선택 요일 유형을 총합으로 볼지 평균으로 볼지 제어합니다.)
   */
  if (dayAggregation) {
    params.append("dayAggregation", dayAggregation);
  }

  /**
   * Multiple lines and hours are sent as comma-separated strings.
   * (다중 노선과 시간대는 쉼표로 구분된 문자열로 전송합니다.)
   *
   * This keeps the URL compact and matches the backend parser.
   * (URL을 간결하게 유지하고 백엔드 파서와 맞추기 위한 방식입니다.)
   */
  if (Array.isArray(lines) && lines.length > 0) {
    params.append("lines", lines.join(","));
  }

  if (Array.isArray(hours) && hours.length > 0) {
    params.append("hours", hours.join(","));
  }

  const response = await fetch(
    `/api/map/demand?${params.toString()}`
  );

  if (!response.ok) {
    throw new Error(
      `Failed to fetch map demand: ${response.status}`
    );
  }

  return response.json();
}

/**
 * Calls the existing map demand endpoint for selected subway and bus routes,
 * then merges the results on the frontend.
 * (선택된 지하철/버스 노선에 대해 기존 지도 수요 API를 각각 호출한 뒤 프론트엔드에서 결과를 병합합니다.)
 */
export async function fetchMultiModeMapDemand({
  selectedSubwayLines,
  selectedBusLines,
  dayTypes,
  dayAggregation,
  hours
}) {
  const requests = [];

  if (Array.isArray(selectedSubwayLines) && selectedSubwayLines.length > 0) {
    requests.push(
      fetchMapDemand({
        mode: "subway",
        lines: selectedSubwayLines,
        dayTypes,
        dayAggregation,
        hours
      })
    );
  }

  if (Array.isArray(selectedBusLines) && selectedBusLines.length > 0) {
    requests.push(
      fetchMapDemand({
        mode: "bus",
        lines: selectedBusLines,
        dayTypes,
        dayAggregation,
        hours
      })
    );
  }

  const results = await Promise.all(requests);
  return results.flat();
}
