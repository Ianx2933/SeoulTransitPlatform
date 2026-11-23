import { useI18n } from "../i18n/I18nProvider.jsx";
import { OPERATOR, PRIVACY_EFFECTIVE } from "../site.js";
import { PRIVACY_SECTIONS } from "../i18n/messagesAbout.js";
import { ContactLinks } from "./AboutView.jsx";

/** Privacy policy page; its address (#/privacy) is the one to give ad networks. */
export default function PrivacyView() {
  const { t } = useI18n();
  return (
    <div className="page">
      <section className="page-card">
        <h1 className="page-title">{t("privacy.title")}</h1>
        <p className="note">{t("privacy.effective", { date: PRIVACY_EFFECTIVE })}</p>
        <p className="download-intro">{t("privacy.intro", { name: OPERATOR })}</p>
        {PRIVACY_SECTIONS.map((key) => (
          <section key={key}>
            <h2 className="download-subtitle">{t(`privacy.sections.${key}.title`)}</h2>
            <p className="download-desc">{t(`privacy.sections.${key}.body`, { name: OPERATOR })}</p>
          </section>
        ))}
        <ContactLinks />
      </section>
    </div>
  );
}
