/**
 * Calls the Spring Boot map demand API.
 *
 * Vite proxy sends `/api/...` requests to `http://localhost:8080`.
 */
export async function fetchMapDemand({
  mode,
  line,
  dayType,
  hour
}) {
  const params = new URLSearchParams({
    mode,
    dayType,
    hour: String(hour)
  });

  if (line && line.trim() !== "") {
    params.append("line", line.trim());
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