import { useEffect, useState } from "react";

import { useI18n } from "../i18n/I18nProvider.jsx";
import {
  OPEN_DATA_BASE,
  fileUrl,
  formatBytes,
  formatDay,
  manifestUrl,
  parseManifest,
  pickText
} from "../utils/openData.js";

/**
 * Data download page: the files of the newest open data release, read from
 * latest/manifest.json in the public bucket, with their attribution.
 */
export default function DownloadView() {
  const { t, lang } = useI18n();
  const [state, setState] = useState({ status: "loading", manifest: null });

  useEffect(() => {
    const controller = new AbortController();
    fetch(manifestUrl(), { signal: controller.signal, cache: "no-cache" })
      .then((response) => {
        if (!response.ok) throw new Error(String(response.status));
        return response.json();
      })
      .then((json) => setState({ status: "ready", manifest: parseManifest(json) }))
      .catch((error) => {
        if (error.name !== "AbortError") setState({ status: "error", manifest: null });
      });
    return () => controller.abort();
  }, []);

  const m = state.manifest;
  const number = new Intl.NumberFormat(lang === "ko" ? "ko-KR" : "en-US");

  return (
    <div className="page">
      <section className="page-card page-card--wide">
        <h1 className="page-title">{t("download.title")}</h1>
        <p className="download-intro">{t("download.intro")}</p>

        {state.status === "loading" && <p className="note">{t("download.loading")}</p>}
        {state.status === "error" && <p className="note" role="alert">{t("download.error")}</p>}

        {m && (
          <>
            <p className="note">
              {t("download.release", {
                start: formatDay(m.window_start),
                end: formatDay(m.window_end),
                generated: String(m.generated_at || "").slice(0, 10)
              })}
            </p>
            <ul className="download-list">
              {m.files.map((f) => (
                <li key={f.file} className="download-item">
                  <div className="download-head">
                    <a href={fileUrl(OPEN_DATA_BASE, m.release, f.file)} download>
                      {f.file}
                    </a>
                    <span className="note">
                      {t("download.rows", { rows: number.format(f.rows ?? 0) })}
                      {f.bytes != null && ` · ${formatBytes(f.bytes)}`}
                    </span>
                  </div>
                  <p className="download-desc">{pickText(f.description, lang)}</p>
                  <details>
                    <summary>{t("download.columns", { n: (f.columns || []).length })}</summary>
                    <ul className="download-columns">
                      {(f.columns || []).map((c) => (
                        <li key={c}>
                          <code>{c}</code>
                          {f.notes?.[c] && <span className="note"> — {f.notes[c]}</span>}
                        </li>
                      ))}
                    </ul>
                  </details>
                </li>
              ))}
            </ul>

            <h2 className="download-subtitle">{t("download.howTitle")}</h2>
            <ul className="method-list">
              {[t("download.howCsv"), t("download.howBigQuery", { dataset: m.bigquery_dataset || "" }), t("download.howWeekly")].map(
                (item) => (
                  <li key={item}>{item}</li>
                )
              )}
            </ul>

            <h2 className="download-subtitle">{t("download.licenseTitle")}</h2>
            <p className="download-desc">{pickText(m.attribution, lang)}</p>
            <p className="note">{t("download.licenseNote")}</p>
          </>
        )}
      </section>
    </div>
  );
}
