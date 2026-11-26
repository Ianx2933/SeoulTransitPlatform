import { useI18n } from "../i18n/I18nProvider.jsx";
import { fleetMetrics, fleetNumber } from "../utils/fleet.js";

/**
 * Vehicles and trips of the route, typed in by hand, and what they give with
 * the riders on screen: riders per trip, on board per trip on the busiest
 * section, riders per vehicle. With hours chosen, trips are those hours' trips.
 */
export default function FleetInputs({ routeData, hours, fleet, setFleet, fmt }) {
  const { t } = useI18n();
  const stops = routeData?.stops || [];
  const maxOnBoard = stops.reduce((m, s) => Math.max(m, Number(s.onBoardAfter) || 0), 0);
  const m = fleetMetrics({
    total: Number(routeData?.totalPassengers) || 0,
    maxOnBoard,
    vehicles: fleet.vehicles,
    trips: fleet.trips
  });
  const set = (key) => (e) => setFleet({ ...fleet, [key]: fleetNumber(e.target.value), [`${key}Text`]: e.target.value });
  const text = (key) => fleet[`${key}Text`] ?? (fleet[key] ? String(fleet[key]) : "");
  const one = (v) => fmt(Math.round(v * 10) / 10);

  return (
    <div className="fleet">
      <div className="road-controls">
        <label className="field-inline">
          {t("route.fleet.vehicles")}
          <input type="text" inputMode="numeric" className="field-inline__narrow" value={text("vehicles")} onChange={set("vehicles")} />
          {t("route.fleet.vehicleUnit")}
        </label>
        <label className="field-inline">
          {hours ? t("route.fleet.tripsHours") : t("route.fleet.tripsDay")}
          <input type="text" inputMode="numeric" className="field-inline__narrow" value={text("trips")} onChange={set("trips")} />
          {t("route.fleet.tripUnit")}
        </label>
      </div>
      {(m.perTrip !== null || m.perVehicle !== null) && (
        <ul className="fleet__figures">
          {m.perTrip !== null && <li>{t("route.fleet.perTrip", { value: one(m.perTrip) })}</li>}
          {m.maxPerTrip !== null && <li>{t("route.fleet.maxPerTrip", { value: one(m.maxPerTrip) })}</li>}
          {m.perVehicle !== null && <li>{t("route.fleet.perVehicle", { value: one(m.perVehicle) })}</li>}
          {m.tripsPerVehicle !== null && <li>{t("route.fleet.tripsPerVehicle", { value: one(m.tripsPerVehicle) })}</li>}
        </ul>
      )}
      <p className="note">{t("route.fleet.note")}</p>
    </div>
  );
}
