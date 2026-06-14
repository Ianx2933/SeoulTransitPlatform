/**
 * Sort utilities for the node detail panel.
 *
 * Both sorters return a new array and never mutate the input, so they are
 * safe to call inside React hooks such as useMemo.
 */

/**
 * Sorts nearby catchment nodes.
 */
export function sortCatchmentNodes(nodes, sortKey) {
  const copiedNodes = [...nodes];

  switch (sortKey) {
    case "distanceAsc":
      return copiedNodes.sort(
        (a, b) => Number(a.distanceMeters || 0) - Number(b.distanceMeters || 0)
      );
    case "distanceDesc":
      return copiedNodes.sort(
        (a, b) => Number(b.distanceMeters || 0) - Number(a.distanceMeters || 0)
      );
    case "totalDesc":
      return copiedNodes.sort(
        (a, b) => Number(b.total || 0) - Number(a.total || 0)
      );
    case "totalAsc":
      return copiedNodes.sort(
        (a, b) => Number(a.total || 0) - Number(b.total || 0)
      );
    case "nameAsc":
      return copiedNodes.sort((a, b) => compareText(a.nodeName, b.nodeName));
    case "nameDesc":
      return copiedNodes.sort((a, b) => compareText(b.nodeName, a.nodeName));
    default:
      return copiedNodes;
  }
}

/**
 * Sorts route breakdown rows.
 */
export function sortRoutes(routes, sortKey) {
  const copiedRoutes = [...routes];

  switch (sortKey) {
    case "totalDesc":
      return copiedRoutes.sort(
        (a, b) => Number(b.total || 0) - Number(a.total || 0)
      );
    case "totalAsc":
      return copiedRoutes.sort(
        (a, b) => Number(a.total || 0) - Number(b.total || 0)
      );
    case "routeAsc":
      return copiedRoutes.sort((a, b) =>
        compareRouteId(a.serviceId, b.serviceId)
      );
    case "routeDesc":
      return copiedRoutes.sort((a, b) =>
        compareRouteId(b.serviceId, a.serviceId)
      );
    case "boardingDesc":
      return copiedRoutes.sort(
        (a, b) => Number(b.boarding || 0) - Number(a.boarding || 0)
      );
    case "alightingDesc":
      return copiedRoutes.sort(
        (a, b) => Number(b.alighting || 0) - Number(a.alighting || 0)
      );
    default:
      return copiedRoutes;
  }
}

/**
 * Compares route IDs with locale-aware, numeric-sensitive ordering.
 */
function compareRouteId(first, second) {
  return String(first || "").localeCompare(String(second || ""), "ko", {
    numeric: true,
    sensitivity: "base"
  });
}

/**
 * Compares display text with the same locale-aware ordering as route IDs.
 */
function compareText(first, second) {
  return String(first || "").localeCompare(String(second || ""), "ko", {
    numeric: true,
    sensitivity: "base"
  });
}
