import { useState } from "react";

import { useI18n } from "../i18n/I18nProvider.jsx";
import MethodologyPanel from "./MethodologyPanel.jsx";
import MetricHelpTooltip from "./MetricHelpTooltip.jsx";

/** Explains data coverage, known limitations, and how to read the map. */
export default function DataCoverageNotice() {
  const { t, list } = useI18n();
  const [expanded, setExpanded] = useState(false);

  return (
    <section className="notice-card">
      <div className="notice-card__head">
        <div>
          <h2 className="section-title">{t("notice.title")}</h2>
          <p className="note">{t("notice.summary")}</p>
        </div>
        <button
          type="button"
          className="button"
          onClick={() => setExpanded((v) => !v)}
          aria-expanded={expanded}
          aria-controls="coverage-details"
        >
          {expanded ? t("notice.hide") : t("notice.show")}
        </button>
      </div>

      {expanded && (
        <div id="coverage-details" className="notice-card__body">
          <section>
            <h3 className="flow-title">{t("notice.whatTitle")}</h3>
            <p className="body-text">{t("notice.what")}</p>
          </section>
          <section>
            <h3 className="flow-title">{t("notice.metricTitle")}</h3>
            <div className="metric-help-row">
              <MetricHelpTooltip label={t("map.metric.total")} description={t("notice.metricTotal")} />
              <MetricHelpTooltip label={t("map.metric.boarding")} description={t("notice.metricBoarding")} />
              <MetricHelpTooltip label={t("map.metric.alighting")} description={t("notice.metricAlighting")} />
            </div>
          </section>
          <section>
            <h3 className="flow-title">{t("notice.coverageTitle")}</h3>
            <ul className="method-list">
              {list("notice.coverage").map((item) => <li key={item}>{item}</li>)}
            </ul>
          </section>
          <section>
            <h3 className="flow-title">{t("notice.limitsTitle")}</h3>
            <ul className="method-list">
              {list("notice.limits").map((item) => <li key={item}>{item}</li>)}
            </ul>
          </section>
          <MethodologyPanel />
        </div>
      )}
    </section>
  );
}
