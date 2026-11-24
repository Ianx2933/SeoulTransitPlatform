/** Stops sorted by riders (most first), by stop number, or by name; ties by stop number. Pure, for tests. */
export function sortFlows(flows, by, lang = "ko") {
  const out = [...(flows || [])];
  const bySeq = (a, b) => a.seq - b.seq;
  if (by === "seq") return out.sort(bySeq);
  if (by === "name") {
    return out.sort((a, b) => String(a.name || "").localeCompare(String(b.name || ""), lang) || bySeq(a, b));
  }
  return out.sort((a, b) => b.passengers - a.passengers || bySeq(a, b));
}
