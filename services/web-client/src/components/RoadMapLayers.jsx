import RoadPointsLayer from "./RoadPointsLayer.jsx";
import RouteLoadLayer from "./RouteLoadLayer.jsx";
import RouteLoadLegend from "./RouteLoadLegend.jsx";

/**
 * The road layer inside the map (lines, and the board/alight circles of a
 * look-up). road is useRoadLayer(). A look-up shows even with district
 * boundaries off; the district result needs them on.
 */
export function RoadMapLayers({ road, boundariesShown }) {
  if (!road.styled || !(road.exploreLoad.result || boundariesShown)) return null;
  return (
    <>
      <RouteLoadLayer result={road.shownResult} segments={road.styled.segments} onSegmentClick={road.exploreSegment} />
      {road.exploreLoad.result && <RoadPointsLayer result={road.exploreLoad.result} />}
    </>
  );
}

/** The ten-step scale over the map's lower left corner, outside MapContainer. */
export function RoadLegendOverlay({ road, boundariesShown }) {
  const { styled } = road;
  if (!styled || (styled.shown === 0 && !styled.fixed) || !(road.exploreLoad.result || boundariesShown)) return null;
  return (
    <div className="map-legend-overlay">
      <RouteLoadLegend styled={styled} minLoad={road.minLoad} hours={road.hours} />
    </div>
  );
}
