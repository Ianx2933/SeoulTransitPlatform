/**
 * Seoul bus route types from the route number, with the colours of Seoul's
 * bus colour system. Kept free of React so it can be unit-tested.
 *
 *   3 digits                      trunk (간선)       #0068B7
 *   4 digits starting 1-7         branch (지선)      #53B332
 *   4 digits starting 9           express (광역)     #E60012
 *   2 digits                      circular (순환)    #F2B70A
 *   district name + number        village (마을)     #53B332
 *   N prefix                      night (심야)       #FFC600 on #3D5BAB
 *   Han River bus                 ferry (한강버스)   #0080FF
 *
 * An A prefix (autonomous bus) is ignored and the rest of the number decides,
 * except A21, which runs as a trunk route. A suffix after the digits
 * ("9403-1") does not change the type. Anything else is "other".
 *
 * Text colours: dark text on green and yellow, which white text does not
 * reach a readable contrast on.
 */
export const BUS_ROUTE_STYLES = {
  trunk: { bg: "#0068B7", fg: "#FFFFFF" },
  branch: { bg: "#53B332", fg: "#1F2328" },
  express: { bg: "#E60012", fg: "#FFFFFF" },
  circular: { bg: "#F2B70A", fg: "#1F2328" },
  village: { bg: "#53B332", fg: "#1F2328" },
  night: { bg: "#3D5BAB", fg: "#FFC600" },
  ferry: { bg: "#0080FF", fg: "#FFFFFF" },
  other: { bg: "#5B6470", fg: "#FFFFFF" }
};

const EXCEPTIONS = { A21: "trunk" };

export function busRouteType(name, mode = "bus") {
  const text = String(name ?? "").trim().toUpperCase();
  if (mode === "ferry" || text.includes("한강")) return "ferry";
  if (!text) return "other";
  if (EXCEPTIONS[text]) return EXCEPTIONS[text];
  if (text.startsWith("N")) return "night";
  const rest = text.startsWith("A") ? text.slice(1) : text;
  if (/^[가-힣]+\d/.test(rest)) return "village";
  const digits = (rest.match(/^\d+/) || [""])[0];
  if (digits.length === 3) return "trunk";
  if (digits.length === 2) return "circular";
  if (digits.length === 4) {
    if (/^[1-7]/.test(digits)) return "branch";
    if (digits.startsWith("9")) return "express";
  }
  return "other";
}

export function busRouteStyle(name, mode = "bus") {
  return BUS_ROUTE_STYLES[busRouteType(name, mode)];
}
