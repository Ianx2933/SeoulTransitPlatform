/**
 * Message lookup kept free of React so it can be unit-tested.
 *
 * Keys are dotted paths into a nested dictionary ("route.reach.label").
 * A missing key falls back to the fallback dictionary, then to the key itself,
 * so a forgotten translation shows up as readable English or as the key
 * instead of an empty string. {name} placeholders are filled from vars.
 */
export const LANGUAGES = ["ko", "en"];
export const DEFAULT_LANGUAGE = "ko";

export function lookup(dict, key) {
  let node = dict;
  for (const part of key.split(".")) {
    if (node == null || typeof node !== "object") return undefined;
    node = node[part];
  }
  return typeof node === "string" ? node : undefined;
}

export function interpolate(text, vars) {
  if (!vars) return text;
  return text.replace(/\{(\w+)\}/g, (match, name) =>
    vars[name] === undefined || vars[name] === null ? match : String(vars[name])
  );
}

export function makeTranslator(dict, fallback) {
  return (key, vars) => {
    const text = lookup(dict, key) ?? lookup(fallback, key) ?? key;
    return interpolate(text, vars);
  };
}

/** A stored or browser language if it is supported, otherwise the default. */
export function pickLanguage(stored, browser) {
  if (LANGUAGES.includes(stored)) return stored;
  const short = String(browser || "").slice(0, 2).toLowerCase();
  if (short === "en") return "en";
  return DEFAULT_LANGUAGE;
}
