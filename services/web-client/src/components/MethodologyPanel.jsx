import { useI18n } from "../i18n/I18nProvider.jsx";

/**
 * Methodology notes in the interface language.
 *
 * The map shows hourly boarding demand; route analysis shows typical-day OD,
 * optionally for one boarding hour. Crowding against capacity is planned.
 */
export default function MethodologyPanel({ asPage = false }) {
  const { t, list } = useI18n();
  const Heading = asPage ? "h1" : "h3";
  return (
    <section className={asPage ? "page-card" : "methodology-panel"}>
      <Heading className={asPage ? "page-title" : undefined}>{t("method.title")}</Heading>
      <ul className="method-list">
        {list("method.items").map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </section>
  );
}
