/**
 * Picking stops of a route on the chart, the list or the map. A click picks
 * one stop; Ctrl (⌘ on a Mac) adds or removes one; Shift picks every stop
 * from the first picked one to the clicked one, by stop number (with Ctrl as
 * well, the range is added to what is picked). Pure, for tests.
 *
 * pick = { anchor, seqs }: anchor is the stop Shift counts from; seqs sorted.
 */
export const NO_PICK = { anchor: null, seqs: [] };
export const MAX_PICKED = 200;

export function modifiers(event) {
  const e = event?.originalEvent || event || {};
  return { shift: Boolean(e.shiftKey), ctrl: Boolean(e.ctrlKey || e.metaKey) };
}

const sorted = (xs) => [...new Set(xs)].sort((a, b) => a - b).slice(0, MAX_PICKED);

export function nextPick(pick, seq, { shift = false, ctrl = false } = {}, allSeqs = []) {
  const current = pick || NO_PICK;
  if (shift && current.anchor != null) {
    const lo = Math.min(current.anchor, seq);
    const hi = Math.max(current.anchor, seq);
    const range = (allSeqs.length ? allSeqs : [lo, hi]).filter((s) => s >= lo && s <= hi);
    return { anchor: current.anchor, seqs: sorted(ctrl ? [...current.seqs, ...range] : range) };
  }
  if (ctrl) {
    if (current.seqs.includes(seq)) {
      const seqs = current.seqs.filter((s) => s !== seq);
      return { anchor: current.anchor === seq ? (seqs[0] ?? null) : current.anchor, seqs };
    }
    return { anchor: current.anchor ?? seq, seqs: sorted([...current.seqs, seq]) };
  }
  return { anchor: seq, seqs: [seq] };
}

/** The single stop the stop card shows: the only picked one, else none. */
export function singleSeq(pick) {
  return pick && pick.seqs.length === 1 ? pick.seqs[0] : null;
}

/** Two stops closer than this (metres) are one place on the map. */
export const SAME_PLACE_M = 25;

/**
 * Stops that share a place on the map: the same ARS number, or points within
 * SAME_PLACE_M of each other (a terminus served at both ends, a stop both
 * directions use, the two sides of a narrow street). Their markers would sit
 * on top of each other and only the last one drawn could be clicked, so each
 * group is drawn once and lets the user choose. Groups keep route order.
 */
export function groupByPlace(placed) {
  const groups = [];
  const byArs = new Map();
  for (const p of placed || []) {
    const ars = String(p.ars ?? "").trim();
    let group = ars ? byArs.get(ars) : undefined;
    if (!group) group = groups.find((g) => metres(g[0], p) < SAME_PLACE_M);
    if (!group) {
      group = [];
      groups.push(group);
    }
    group.push(p);
    if (ars && !byArs.has(ars)) byArs.set(ars, group);
  }
  return groups;
}

function metres(a, b) {
  const lat = ((Number(a.lat) + Number(b.lat)) / 2) * (Math.PI / 180);
  const dy = (Number(a.lat) - Number(b.lat)) * 111320;
  const dx = (Number(a.lng) - Number(b.lng)) * 111320 * Math.cos(lat);
  return Math.sqrt(dx * dx + dy * dy);
}
