/**
 * Interface text for the demand map page (control panel, search, notes).
 * Merged into messages.js.
 */
export const mapMessages = {
  ko: {
    map: {
      title: "지도 수요",
      intro: "지하철·버스 노선, 요일, 시간대, 지표를 고른 뒤 수요 불러오기를 누르세요.",
      days: {
        mon: "월요일",
        tue: "화요일",
        wed: "수요일",
        thu: "목요일",
        fri: "금요일",
        sat: "토요일",
        sun_holiday: "일요일·공휴일"
      },
      presets: {
        label: "요일 묶음",
        mon: "월요일",
        tue: "화요일",
        wed: "수요일",
        thu: "목요일",
        fri: "금요일",
        weekdays: "평일(월~금)",
        weekend: "주말·공휴일",
        all: "모든 요일",
        custom: "직접 고르기"
      },
      customDays: "요일 직접 고르기",
      aggregation: {
        label: "여러 요일 합치기",
        average: "고른 요일 하루 평균",
        sum: "고른 요일 합계"
      },
      hours: {
        start: "시작 {hour}:00",
        end: "끝 {hour}:00"
      },
      month: {
        label: "달",
        typical: "대표값 (전체 기간, 요일별)",
        option: "{month} (버스)",
        note: "{month} 버스: 그 달 합계를 정류소별 요일 비율로 나눈 하루입니다. 정류소 상세·반경·행정동 분석은 대표값을 씁니다.",
        noSubway: "지하철은 달별 시간대 자료가 없어 지도에서 빠집니다."
      },
      metric: {
        label: "지표",
        total: "승하차 합계",
        boarding: "승차",
        alighting: "하차"
      },
      boundary: "행정동 경계 보기",
      districtTitle: "행정동 분석",
      districtNote: "행정동 경계를 켜야 경계를 누를 수 있습니다. 고른 행정동은 지금 불러온 지도 지점을 걸러 보여 주고, 반경 수요는 반경 기준 그대로입니다.",
      routes: {
        subway: "지하철 노선",
        bus: "버스 노선",
        search: "노선 검색",
        subwayPlaceholder: "예: 2호선",
        busPlaceholder: "예: 741, 9401",
        clear: "선택 지우기",
        loading: "노선 불러오는 중…",
        remove: "{name} 선택 빼기"
      },
      load: "수요 불러오기",
      status: {
        loaded: "수요를 불러왔습니다. 조건을 바꾸면 다시 불러오기를 누르세요.",
        empty: "아직 불러온 수요가 없습니다."
      },
      counts: "지하철 {subway}개 · 버스 {bus}개 노선 · 요일 {days}개",
      error: {
        selectRoute: "수요를 불러오려면 지하철이나 버스 노선을 하나 이상 고르세요.",
        selectDay: "수요를 불러오려면 요일을 하나 이상 고르세요.",
        selectDayNode: "정류소 분석을 열려면 요일을 하나 이상 고르세요."
      }
    },
    search: {
      title: "정류소·역 검색",
      keyword: "검색어",
      placeholder: "예: 강남, 서현, 07616",
      submit: "검색",
      searching: "검색 중…",
      hint: "노선을 고르지 않아도 정류소 분석을 열 수 있습니다.",
      tooShort: "두 글자 이상 입력하세요.",
      results: "검색 결과",
      modes: { subway: "지하철", bus: "버스", ferry: "한강버스" }
    },
    notice: {
      title: "데이터 범위와 읽는 법",
      summary: "좌표가 확인된 정류소·역의 수요만 보여 줍니다.",
      show: "자세히 보기",
      hide: "접기",
      whatTitle: "이 지도가 보여 주는 것",
      what: "고른 지하철·버스 노선, 요일, 요일 합치기 방식, 시간대에 대한 승차·하차·합계 수요를 모아 보여 줍니다.",
      metricTitle: "지표 설명",
      metricTotal: "고른 시간대의 승차와 하차를 더한 수요입니다.",
      metricBoarding: "정류소·역마다 탄 사람 수입니다.",
      metricAlighting: "정류소·역마다 내린 사람 수입니다.",
      coverageTitle: "범위",
      coverage: [
        "지하철과 버스 노선을 함께 띄워 비교해 볼 수 있습니다.",
        "정류소·역 검색으로 노선을 고르지 않고도 정류소 중심 분석을 열 수 있습니다.",
        "정류소나 역을 누르면 그곳을 지나는 모든 노선의 상세와 반경 수요가 열립니다.",
        "반경 요약, 주변 정류소, 반경 안 노선은 같은 반경 계산 결과를 따로 켜고 끄는 표시입니다.",
        "노선별 내역의 노선은 노선 선택에 다시 넣어 지도에 불러올 수 있습니다.",
        "행정동 경계는 경계 표시를 켰을 때만 누를 수 있습니다.",
        "분석 조건을 적용한 뒤 행정동을 누르면 그 행정동 안 모든 노선의 수요가 열립니다.",
        "고른 행정동은 수요를 다시 불러와도 유지되고, 행정동 수요도 새 조건으로 다시 계산됩니다.",
        "좌표가 있는 정류소·역만 지도에 그립니다."
      ],
      limitsTitle: "한계",
      limits: [
        "반경 수요는 탐색용입니다. 공식 환승 구역, 역세권, 법적 서비스 구역이 아닙니다.",
        "반경 수요는 고른 행정동 경계로 자르지 않습니다.",
        "행정동 수요는 경계 안 정류소·역의 승하차이지 행정동 사이 OD가 아닙니다.",
        "고른 행정동은 행정동 수요와 별도로, 지금 불러온 지도 지점도 걸러 보여 줍니다.",
        "행정동 수요 목록은 화면에서 잘릴 수 있지만 합계와 내역은 항상 맞은 모든 행으로 계산하고, 잘렸으면 패널에 표시합니다.",
        "경계와 맞지 않는 행정동 코드는 0으로 넣지 않고 경고로 알립니다.",
        "선택 노선에 추가하면 노선 선택만 바뀌고, 지도는 수요 불러오기를 눌러야 바뀝니다.",
        "수요 값은 차내 혼잡, 차량 부하, 빈 좌석을 직접 나타내지 않습니다."
      ]
    }
  },
  en: {
    map: {
      title: "Demand map",
      intro: "Pick subway and bus routes, days, hours and a metric, then load demand.",
      days: {
        mon: "Monday",
        tue: "Tuesday",
        wed: "Wednesday",
        thu: "Thursday",
        fri: "Friday",
        sat: "Saturday",
        sun_holiday: "Sunday & holiday"
      },
      presets: {
        label: "Days",
        mon: "Monday",
        tue: "Tuesday",
        wed: "Wednesday",
        thu: "Thursday",
        fri: "Friday",
        weekdays: "Weekdays (Mon–Fri)",
        weekend: "Weekend & holiday",
        all: "All days",
        custom: "Pick days"
      },
      customDays: "Pick days",
      aggregation: {
        label: "Combine days",
        average: "Mean per chosen day",
        sum: "Sum of chosen days"
      },
      hours: {
        start: "From {hour}:00",
        end: "To {hour}:00"
      },
      month: {
        label: "Month",
        typical: "Typical (all months, by day type)",
        option: "{month} (bus)",
        note: "{month}, buses: the month's total split into days by each stop's day-type shape. Stop detail, radius and district analysis use the typical figures.",
        noSubway: "The subway has no monthly hourly data and is left off the map."
      },
      metric: {
        label: "Metric",
        total: "Boarding + alighting",
        boarding: "Boarding",
        alighting: "Alighting"
      },
      boundary: "Show district boundaries",
      districtTitle: "District analysis",
      districtNote: "District boundaries can be clicked only while they are shown. Chosen districts filter the map points already loaded; catchment demand stays radius-based.",
      routes: {
        subway: "Subway routes",
        bus: "Bus routes",
        search: "Search routes",
        subwayPlaceholder: "e.g. 2호선",
        busPlaceholder: "e.g. 741, 9401",
        clear: "Clear",
        loading: "Loading routes…",
        remove: "Remove {name}"
      },
      load: "Load demand",
      status: {
        loaded: "Demand loaded. Change the filters and load again to update.",
        empty: "No demand loaded yet."
      },
      counts: "{subway} subway · {bus} bus routes · {days} days",
      error: {
        selectRoute: "Pick at least one subway or bus route to load demand.",
        selectDay: "Pick at least one day to load demand.",
        selectDayNode: "Pick at least one day to open stop analysis."
      }
    },
    search: {
      title: "Stop and station search",
      keyword: "Search",
      placeholder: "e.g. 강남, 서현, 07616",
      submit: "Search",
      searching: "Searching…",
      hint: "Opens stop analysis without picking a route first.",
      tooShort: "Enter at least two characters.",
      results: "Results",
      modes: { subway: "Subway", bus: "Bus", ferry: "Han River bus" }
    },
    notice: {
      title: "Data coverage and how to read it",
      summary: "Shows demand only at stops and stations with known coordinates.",
      show: "Show details",
      hide: "Hide details",
      whatTitle: "What this map shows",
      what: "Boarding, alighting and total demand for the chosen subway and bus routes, days, way of combining days, and hours.",
      metricTitle: "Metrics",
      metricTotal: "Boardings plus alightings in the chosen hours.",
      metricBoarding: "Riders getting on at each stop or station.",
      metricAlighting: "Riders getting off at each stop or station.",
      coverageTitle: "Coverage",
      coverage: [
        "Subway and bus routes can be shown together for comparison.",
        "Stop and station search opens stop analysis without picking a route first.",
        "Clicking a stop or station opens detail for every route serving it and radius-based catchment demand.",
        "Catchment summary, nearby stops and routes in the radius are separate views of the same radius calculation.",
        "Routes in a breakdown can be added back to the route picker for another map load.",
        "District boundaries can be clicked only while they are shown.",
        "With filters applied, clicking a district opens demand for every route inside it.",
        "Chosen districts stay chosen when demand is reloaded, and their demand is recalculated with the new filters.",
        "Only stops and stations with coordinates are drawn."
      ],
      limitsTitle: "Limitations",
      limits: [
        "Radius-based catchment is exploratory. It is not an official transfer area, station catchment or legal service area.",
        "Catchment demand is not clipped to the chosen district boundaries.",
        "District demand is boarding and alighting at stops inside the boundaries, not district-to-district OD.",
        "Chosen districts also filter the loaded map points, separately from district demand.",
        "District demand lists may be cut short on screen, but totals and breakdowns always use every matched row, and any cut is labelled.",
        "District codes that match no boundary are reported as a warning instead of counting as zero.",
        "Adding to the chosen routes changes the route picker only; the map changes after Load demand.",
        "Demand values do not directly show crowding on board, vehicle load or free seats."
      ]
    }
  }
};
