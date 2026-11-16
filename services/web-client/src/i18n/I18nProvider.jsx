import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

import { koByEnglish } from "./koByEnglish.js";
import { messages } from "./messages.js";
import { DEFAULT_LANGUAGE, interpolate, makeTranslator, pickLanguage } from "./translate.js";

const STORAGE_KEY = "stp.lang";

const I18nContext = createContext({
  lang: DEFAULT_LANGUAGE,
  setLang: () => {},
  t: makeTranslator(messages[DEFAULT_LANGUAGE], messages.en),
  tx: (text, vars) => interpolate(text, vars),
  list: () => []
});

function readStored() {
  try {
    return window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

/**
 * Holds the interface language. The choice is kept in localStorage and
 * mirrored to <html lang> so screen readers switch pronunciation.
 */
export function I18nProvider({ children }) {
  const [lang, setLangState] = useState(() =>
    pickLanguage(readStored(), typeof navigator !== "undefined" ? navigator.language : "")
  );

  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);

  const setLang = useCallback((next) => {
    setLangState(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Private browsing can refuse storage; the choice then lasts for the visit.
    }
  }, []);

  const value = useMemo(() => {
    const dict = messages[lang] || messages[DEFAULT_LANGUAGE];
    const list = (key) => {
      const node = key.split(".").reduce((n, part) => (n ? n[part] : undefined), dict);
      return Array.isArray(node) ? node : [];
    };
    // tx: text keyed by its English wording (map-page panels).
    const tx = (text, vars) => interpolate(lang === "ko" ? koByEnglish[text] ?? text : text, vars);
    return { lang, setLang, t: makeTranslator(dict, messages.en), tx, list };
  }, [lang, setLang]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  return useContext(I18nContext);
}
