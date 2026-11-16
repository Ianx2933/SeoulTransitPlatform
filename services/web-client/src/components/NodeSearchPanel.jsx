import { useState } from "react";

import { fetchNodeSearch } from "../api/mapDemandApi.js";
import { useI18n } from "../i18n/I18nProvider.jsx";

/** Search for a stop or station to open stop-centred analysis. */
export default function NodeSearchPanel({ onSelectNode }) {
  const { t } = useI18n();
  const [keyword, setKeyword] = useState("");
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");

  const handleSearch = async (event) => {
    event.preventDefault();
    const trimmed = keyword.trim();
    if (trimmed.length < 2) {
      setErrorMessage(t("search.tooShort"));
      setResults([]);
      return;
    }
    setLoading(true);
    setErrorMessage("");
    try {
      setResults(await fetchNodeSearch({ keyword: trimmed, limit: 30 }));
    } catch (error) {
      setErrorMessage(error.message);
      setResults([]);
    } finally {
      setLoading(false);
    }
  };

  const modeLabel = (mode) => {
    const label = t(`search.modes.${mode}`);
    return label.startsWith("search.") ? mode : label;
  };

  return (
    <section className="sub-card">
      <form onSubmit={handleSearch} className="route-picker__head">
        <h2 className="section-title">{t("search.title")}</h2>
        <label className="field field--inline">
          <span>{t("search.keyword")}</span>
          <input type="text" value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder={t("search.placeholder")} />
        </label>
        <button type="submit" className="button" disabled={loading}>
          {loading ? t("search.searching") : t("search.submit")}
        </button>
        <span className="note">{t("search.hint")}</span>
      </form>
      {errorMessage && <p className="field-error">{errorMessage}</p>}
      {results.length > 0 && (
        <div>
          <h3 className="flow-title">{t("search.results")}</h3>
          <ol className="result-list">
            {results.map((node) => (
              <li key={`${node.mode}-${node.nodeId}-${node.lat}-${node.lng}`}>
                <button type="button" className="result-item" onClick={() => onSelectNode?.({ ...node, source: "node-search" })}>
                  <span className="tag">{modeLabel(node.mode)}</span> {node.nodeName} <span className="note num">{node.nodeId}</span>
                </button>
              </li>
            ))}
          </ol>
        </div>
      )}
    </section>
  );
}
