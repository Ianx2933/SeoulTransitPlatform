import { useState } from "react";

import { useI18n } from "../i18n/I18nProvider.jsx";
import { LANGUAGES } from "../i18n/translate.js";

const NAV = ["map", "route", "method"];
const LANGUAGE_NAMES = { ko: "한국어", en: "English" };

/**
 * Page frame: a left sidebar with the main views and the language switch.
 * Below 900px the sidebar becomes a drawer opened from a top bar.
 */
export default function AppShell({ view, onNavigate, children }) {
  const { t, lang, setLang } = useI18n();
  const [open, setOpen] = useState(false);

  const go = (next) => {
    onNavigate(next);
    setOpen(false);
  };

  return (
    <div className={`shell${open ? " shell--open" : ""}`}>
      <a className="skip-link" href="#main">{t("app.skip")}</a>

      <header className="topbar">
        <button
          type="button"
          className="icon-button"
          aria-label={open ? t("app.menuClose") : t("app.menuOpen")}
          aria-expanded={open}
          aria-controls="sidebar"
          onClick={() => setOpen((v) => !v)}
        >
          <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M4 7h16M4 12h16M4 17h16" />
          </svg>
        </button>
        <span className="brand-name">{t("app.name")}</span>
      </header>

      <nav id="sidebar" className="sidebar" aria-label={t("nav.label")}>
        <div className="brand">
          <svg aria-hidden="true" width="26" height="26" viewBox="0 0 26 26">
            <rect x="1" y="1" width="24" height="24" rx="4" fill="var(--route-up)" />
            <path d="M6 17h14M9 17V9h8v8" stroke="#fff" strokeWidth="2" fill="none" />
            <circle cx="9.5" cy="19.5" r="1.6" fill="#fff" />
            <circle cx="16.5" cy="19.5" r="1.6" fill="#fff" />
          </svg>
          <span className="brand-name">{t("app.name")}</span>
        </div>

        <ul className="nav-list">
          {NAV.map((key) => (
            <li key={key}>
              <a
                href={`#/${key}`}
                className="nav-item"
                aria-current={view === key ? "page" : undefined}
                onClick={(e) => {
                  e.preventDefault();
                  go(key);
                }}
              >
                {t(`nav.${key}`)}
              </a>
            </li>
          ))}
          <li>
            <span className="nav-item nav-item--disabled">
              {t("nav.download")}
              <small>{t("nav.soon")}</small>
            </span>
          </li>
        </ul>

        <div className="lang-switch">
          <span id="lang-label">{t("app.language")}</span>
          <div role="group" aria-labelledby="lang-label" className="segmented">
            {LANGUAGES.map((code) => (
              <button
                key={code}
                type="button"
                lang={code}
                aria-pressed={lang === code}
                onClick={() => setLang(code)}
              >
                {LANGUAGE_NAMES[code]}
              </button>
            ))}
          </div>
        </div>
      </nav>

      <div className="scrim" aria-hidden="true" onClick={() => setOpen(false)} />

      <main id="main" className="content" tabIndex={-1}>
        {children}
      </main>
    </div>
  );
}
