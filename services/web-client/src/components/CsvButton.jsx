import { useI18n } from "../i18n/I18nProvider.jsx";
import { downloadCsv, roadCsv } from "../utils/roadCsv.js";

/**
 * Downloads data as CSV: a road-load result by default (every segment, and
 * stops when present), or anything else with its own toCsv.
 */
export default function CsvButton({ result, fileName, toCsv = roadCsv }) {
  const { tx } = useI18n();
  if (!result) return null;
  return (
    <button type="button" className="chip" onClick={() => downloadCsv(toCsv(result), fileName)}>
      {tx("Download CSV")}
    </button>
  );
}
