/**
 * Calls the Spring Boot map demand API.
 */
export async function fetchMapDemand({
  mode,
  lines,
  dayType,
  dayTypes,
  dayAggregation,
  hours
}) {
  const params = new URLSearchParams({ mode });
  appendOptionalListParam(params, "dayTypes", dayTypes);
  if ((!Array.isArray(dayTypes) || dayTypes.length === 0) && dayType) {
    params.append("dayType", dayType);
  }
  appendOptionalValueParam(params, "dayAggregation", dayAggregation);
  appendOptionalListParam(params, "lines", lines);
  appendOptionalListParam(params, "hours", hours);
  return fetchJson(`/api/map/demand?${params.toString()}`, {
    defaultMessage: "Failed to fetch map demand"
  });
}

/**
 * Calls map demand for subway and bus, then merges results.
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

/**
 * Searches selectable stop or station nodes.
 */
export async function fetchNodeSearch({ keyword, limit = 30 }) {
  const params = new URLSearchParams({ keyword, limit: String(limit) });
  return fetchJson(`/api/map/nodes/search?${params.toString()}`, {
    defaultMessage: "Failed to search nodes"
  });
}

/**
 * Calls the node-centered all-route detail API.
 */
export async function fetchNodeDetail({
  mode,
  nodeId,
  dayTypes,
  dayAggregation,
  hours
}) {
  const params = new URLSearchParams({ mode, nodeId });
  appendOptionalListParam(params, "dayTypes", dayTypes);
  appendOptionalValueParam(params, "dayAggregation", dayAggregation);
  appendOptionalListParam(params, "hours", hours);
  return fetchJson(`/api/map/node-detail?${params.toString()}`, {
    defaultMessage: "Failed to fetch node detail",
    notFoundMessage: "This node is not available in the current dataset."
  });
}

/**
 * Calls the radius-based node catchment API.
 */
export async function fetchNodeCatchment({
  lat,
  lng,
  radiusMeters,
  modes = ["subway", "bus"],
  dayTypes,
  dayAggregation,
  hours
}) {
  const params = new URLSearchParams({
    lat: String(lat),
    lng: String(lng),
    radiusMeters: String(radiusMeters)
  });
  appendOptionalListParam(params, "modes", modes);
  appendOptionalListParam(params, "dayTypes", dayTypes);
  appendOptionalValueParam(params, "dayAggregation", dayAggregation);
  appendOptionalListParam(params, "hours", hours);
  return fetchJson(`/api/map/node-catchment?${params.toString()}`, {
    defaultMessage: "Failed to fetch node catchment"
  });
}


/**
 * Calls the district-centered all-route demand API.
 */
export async function fetchDistrictDemand({
  districtCode,
  districtCodes,
  modes = ["subway", "bus"],
  dayTypes,
  dayAggregation,
  hours,
  nodeLimit
}) {
  const params = new URLSearchParams();
  appendOptionalListParam(params, "districtCodes", districtCodes);
  if ((!Array.isArray(districtCodes) || districtCodes.length === 0) && districtCode) {
    params.append("districtCode", districtCode);
  }
  appendOptionalListParam(params, "modes", modes);
  appendOptionalListParam(params, "dayTypes", dayTypes);
  appendOptionalValueParam(params, "dayAggregation", dayAggregation);
  appendOptionalListParam(params, "hours", hours);
  appendOptionalValueParam(params, "nodeLimit", nodeLimit);
  return fetchJson(`/api/map/district-demand?${params.toString()}`, {
    defaultMessage: "Failed to fetch district demand",
    notFoundMessage: "This district is not available in the current dataset."
  });
}

function appendOptionalListParam(params, key, values) {
  if (Array.isArray(values) && values.length > 0) {
    params.append(key, values.join(","));
  }
}

function appendOptionalValueParam(params, key, value) {
  if (value !== undefined && value !== null && value !== "") {
    params.append(key, value);
  }
}

/**
 * Fetches JSON and normalizes API errors for UI display.
 *
 * The response body is logged to the console for debugging but is not
 * surfaced in the thrown Error message, because raw server responses
 * (including stack traces from 500 errors) should never reach end users.
 */
async function fetchJson(url, {
  defaultMessage,
  notFoundMessage = "Requested resource was not found."
}) {
  let response;
  try {
    response = await fetch(url);
  } catch (error) {
    throw new Error(`Network error: ${error.message}`);
  }
  if (!response.ok) {
    if (response.status === 404) {
      throw new Error(notFoundMessage);
    }
    const responseText = await safeReadText(response);
    if (responseText) {
      console.error(
        `API error at ${url} (status ${response.status}):`,
        responseText
      );
    }
    // Validation errors (4xx) carry a safe, user-actionable message field
    // (e.g. "districtCode must contain 8 to 10 digits"), so surface it.
    // 5xx bodies may contain stack traces and stay console-only.
    if (response.status >= 400 && response.status < 500) {
      const validationMessage = extractErrorMessage(responseText);
      if (validationMessage) {
        throw new Error(`${defaultMessage}: ${validationMessage}`);
      }
    }
    throw new Error(`${defaultMessage}: ${response.status}`);
  }
  return response.json();
}

function extractErrorMessage(responseText) {
  if (!responseText) return "";
  try {
    const parsed = JSON.parse(responseText);
    if (parsed && typeof parsed.message === "string" && parsed.message.trim()) {
      return parsed.message.trim();
    }
  } catch {
    // Non-JSON body: fall through to the generic status message.
  }
  return "";
}

async function safeReadText(response) {
  try {
    return await response.text();
  } catch {
    return "";
  }
}
