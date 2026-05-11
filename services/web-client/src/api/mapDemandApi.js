/**
 * Calls the Spring Boot map demand API.
 *
 * Vite proxy sends `/api/...` requests to `http://localhost:8080`.
 */
export async function fetchMapDemand({
  mode,
  lines,
  dayType,
  hours
}) {
  const params = new URLSearchParams({
    mode,
    dayType
  });

  /**
   * Multiple lines and hours are sent as comma-separated strings.
   * This keeps the URL compact and matches the backend parser.
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
