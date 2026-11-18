import { useI18n } from "../i18n/I18nProvider.jsx";
import { downloadCsv, roadCsv } from "../utils/roadCsv.js";

/** Downloads a road-load result as CSV (every segment, and stops when present). */
export default function CsvButton({ result, fileName }) {
  const { tx } = useI18n();
  if (!result) return null;
  return (
    <button type="button" className="chip" onClick={() => downloadCsv(roadCsv(result), fileName)}>
      {tx("Download CSV")}
    </button>
  );
}
