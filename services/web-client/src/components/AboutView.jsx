import { useI18n } from "../i18n/I18nProvider.jsx";
import { CONTACT_EMAIL, ISSUES_URL, OPERATOR } from "../site.js";

/** GitHub issues link and, when set, the contact email. */
export function ContactLinks() {
  const { t } = useI18n();
  return (
    <p className="contact-links">
      <a href={ISSUES_URL} target="_blank" rel="noopener noreferrer">
        {t("about.reportLink")}
      </a>
      {CONTACT_EMAIL && (
        <>
          {" · "}
          <a href={`mailto:${CONTACT_EMAIL}`}>{t("about.email", { email: CONTACT_EMAIL })}</a>
        </>
      )}
    </p>
  );
}

/** About page: what the site is, how to use it, sources, reuse terms, reporting. */
export default function AboutView() {
  const { t, list } = useI18n();
  return (
    <div className="page">
      <section className="page-card">
        <h1 className="page-title">{t("about.title")}</h1>
        <p className="download-intro">{t("about.intro")}</p>
        <p className="note">{t("about.operator", { name: OPERATOR })}</p>

        <h2 className="download-subtitle">{t("about.useTitle")}</h2>
        <ul className="method-list">
          {list("about.useItems").map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>

        <h2 className="download-subtitle">{t("about.sourceTitle")}</h2>
        <ul className="method-list">
          {list("about.sourceItems").map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>

        <h2 className="download-subtitle">{t("about.reuseTitle")}</h2>
        <p className="download-desc">{t("about.reuseText")}</p>

        <h2 className="download-subtitle">{t("about.reportTitle")}</h2>
        <p className="download-desc">{t("about.reportText")}</p>
        <ContactLinks />

        <p className="note">
          <a href="#/privacy">{t("about.privacyLink")}</a>
        </p>
      </section>
    </div>
  );
}
