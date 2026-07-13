/**
 * Sort utilities for district demand rows.
 *
 * These helpers always return new arrays.
 */

/** Sorts district route rows. */
export function sortDistrictRoutes(routes, sortKey) {
  const copiedRoutes = [...routes];

  switch (sortKey) {
    case "totalDesc":
      return copiedRoutes.sort((a, b) => Number(b.total || 0) - Number(a.total || 0));
    case "totalAsc":
      return copiedRoutes.sort((a, b) => Number(a.total || 0) - Number(b.total || 0));
    case "routeAsc":
      return copiedRoutes.sort((a, b) => compareRouteId(a.serviceId, b.serviceId));
    case "routeDesc":
      return copiedRoutes.sort((a, b) => compareRouteId(b.serviceId, a.serviceId));
    case "boardingDesc":
      return copiedRoutes.sort((a, b) => Number(b.boarding || 0) - Number(a.boarding || 0));
    case "alightingDesc":
      return copiedRoutes.sort((a, b) => Number(b.alighting || 0) - Number(a.alighting || 0));
    default:
      return copiedRoutes;
  }
}

/** Sorts district node rows. */
export function sortDistrictNodes(nodes, sortKey) {
  const copiedNodes = [...nodes];

  switch (sortKey) {
    case "totalDesc":
      return copiedNodes.sort((a, b) => Number(b.total || 0) - Number(a.total || 0));
    case "totalAsc":
      return copiedNodes.sort((a, b) => Number(a.total || 0) - Number(b.total || 0));
    case "boardingDesc":
      return copiedNodes.sort((a, b) => Number(b.boarding || 0) - Number(a.boarding || 0));
    case "alightingDesc":
      return copiedNodes.sort((a, b) => Number(b.alighting || 0) - Number(a.alighting || 0));
    case "nameAsc":
      return copiedNodes.sort((a, b) => compareText(a.nodeName, b.nodeName));
    case "nameDesc":
      return copiedNodes.sort((a, b) => compareText(b.nodeName, a.nodeName));
    case "routeAsc":
      return copiedNodes.sort((a, b) => compareRouteId(a.serviceId, b.serviceId));
    case "routeDesc":
      return copiedNodes.sort((a, b) => compareRouteId(b.serviceId, a.serviceId));
    default:
      return copiedNodes;
  }
}

function compareRouteId(first, second) {
  return String(first || "").localeCompare(String(second || ""), "ko", {
    numeric: true,
    sensitivity: "base"
  });
}

function compareText(first, second) {
  return String(first || "").localeCompare(String(second || ""), "ko", {
    numeric: true,
    sensitivity: "base"
  });
}
