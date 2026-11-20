/**
 * Interface text in Korean and English.
 *
 * Korean is the default. Stop names come from the data and are shown as they
 * are in both languages. Korean uses 정류소 (the term on Seoul's stop signs),
 * not 정류장.
 */
import { mapMessages } from "./messagesMap.js";

export const messages = {
  ko: {
    ...mapMessages.ko,
    app: {
      name: "서울 대중교통 수요",
      skip: "본문으로 건너뛰기",
      menuOpen: "메뉴 열기",
      menuClose: "메뉴 닫기",
      language: "언어"
    },
    nav: {
      label: "주요 메뉴",
      map: "지도 수요",
      route: "노선 분석",
      method: "방법론",
      download: "데이터 내려받기",
      soon: "준비 중"
    },
    busType: {
      trunk: "간선버스",
      branch: "지선버스",
      wide: "광역버스",
      circular: "순환버스",
      village: "마을버스",
      night: "심야버스",
      ferry: "한강버스",
      other: "버스"
    },
    dayType: {
      label: "요일 유형",
      weekday: "평일",
      saturday: "토요일",
      sunday_holiday: "일요일·공휴일"
    },
    busType: {
      trunk: "간선 버스",
      branch: "지선 버스",
      express: "광역 버스",
      circular: "순환 버스",
      village: "마을 버스",
      night: "심야 버스",
      ferry: "한강버스",
      other: "기타 노선"
    },
    route: {
      title: "노선 분석",
      searchLabel: "노선 번호",
      searchPlaceholder: "예: 302",
      submit: "분석하기",
      loading: "불러오는 중…",
      failed: "불러오지 못했습니다",
      enterRoute: "버스 노선 번호를 입력하세요. 예: 302, 452",
      showBoundaries: "행정동 경계 보이기",
      notFound: "{route}번 노선의 이 요일 OD가 없습니다.",
      notFoundHour: "{route}번 노선의 이 시간대 OD가 없습니다.",
      notFoundBasis: "{route}번 노선의 이 기간·시간대 OD가 없습니다.",
      summary: "하루 {total}명 · 정류소 {stops}곳 · 대표일 {days}일 평균",
      summaryHour: "{hour} 승차 {total}명 · 정류소 {stops}곳",
      summaryPeriod: "하루 {total}명 · 정류소 {stops}곳 · {period} 표본 {days}일 평균",
      unordered: "노선 위에 놓을 수 없는 승객 {value}명",
      empty: "노선 번호를 입력하면 정류소마다 버스 안에 몇 명이 타고 있는지 보여 줍니다.",
      dong: {
        title: "행정동 OD",
        show: "행정동 보기",
        hide: "끄기",
        loading: "행정동 OD를 계산하는 중…",
        error: "행정동 OD를 불러오지 못했습니다.",
        meaning: "이 노선 승객만 셉니다. 정류소가 있는 행정동으로 묶었습니다.",
        unplaced: "행정동을 정하지 못한 승객 {value}명",
        pick: "지도에서 행정동을 누르면 그곳 승객이 어디로 가는지 보여 줍니다.",
        clear: "선택 해제",
        totals: "이 노선 승차 {on}명 · 하차 {off}명",
        direction: "방향",
        from: "여기서 탄 사람이 내린 곳",
        to: "여기서 내린 사람이 탄 곳",
        inside: "같은 행정동 안"
      },
      map: {
        empty: "노선을 분석하면 지도에 노선을 그립니다.",
        loading: "정류소 위치를 불러오는 중…",
        error: "정류소 위치를 불러오지 못했습니다. 분석 결과는 그대로 볼 수 있습니다.",
        none: "이 노선의 정류소 위치를 찾지 못했습니다.",
        placed: "정류소 {all}곳 중 {placed}곳을 지도에 그렸습니다. 지도에서 정류소를 누르면 자세히 봅니다.",
        road: "노선은 서울시 버스 노선 정보의 실제 경로로 그렸습니다.",
        straight: "실제 경로를 받지 못해 정류소를 직선으로 이었습니다."
      },
      hour: {
        label: "시간대",
        allDay: "하루 전체",
        pick: "시간대 고르기",
        weekdayOnly: "시간대별 값은 평일만 있습니다. 토요일과 일요일·공휴일은 하루 전체로 봅니다.",
        meaning: "그 시간에 탄 사람만 셉니다.",
        range: "{from}–{to}시"
      },
      chart: {
        title: "정류소를 떠날 때 버스 안에 있는 사람",
        aria: "정류소별 재차 인원 막대그래프",
        hint: "막대를 누르면 그 정류소를 자세히 봅니다.",
        up: "상행",
        down: "하행",
        turnaround: "{seq}번에서 돌아옴(추정)",
        turnaroundBis: "{seq}번에서 돌아옴",
        bar: "{seq} {name}: 떠날 때 {onBoard}명, 승차 {on}명, 하차 {off}명",
        max: "가장 많을 때 {value}명"
      },
      stop: {
        heading: "{seq}번 정류소",
        alight: "내린 사람",
        onBoard: "떠날 때 타고 있는 사람",
        board: "탄 사람",
        destinations: "여기서 탄 사람이 내린 곳",
        origins: "여기서 내린 사람이 탄 곳",
        none: "없음"
      },
      reach: {
        heading: "떠날 때 타고 있던 사람이 어디까지 가나",
        label: "기준 인원",
        input: "기준 인원 입력",
        unit: "명",
        result: "{value}명 넘게 남아 있는 마지막 정류소",
        more: "{count}개 정류소 더 감",
        never: "떠날 때부터 {value}명이 안 됩니다.",
        invalid: "1 이상의 숫자를 넣으세요."
      },
      segment: {
        heading: "구간 분석",
        from: "시작",
        to: "끝",
        submit: "구간 보기",
        title: "{from} {fromName}부터 {to} {toName}까지",
        within: "구간 안에서 타고 내림",
        leaving: "구간에서 타서 밖에서 내림",
        entering: "밖에서 타서 구간에서 내림",
        through: "구간을 지나감",
        share: "노선 전체의 {value}",
        leavingTo: "구간에서 나간 사람이 내린 곳",
        enteringFrom: "구간으로 들어온 사람이 탄 곳"
      },
      table: {
        summary: "정류소 표 ({count}곳)",
        seq: "순번",
        name: "정류소",
        ars: "ARS",
        on: "승차",
        off: "하차",
        onBoard: "떠날 때 재차"
      },
      source:
        "서울시 TOPIS 승하차 OD를 보정해 계산했습니다. TOPIS는 달마다 사흘치 OD를 공개합니다. 최근 12개월에 공개된 날 가운데 같은 요일 유형의 날을 평균했고(평일은 보통 10일), 특수일은 뺐습니다."
    },
    method: {
      title: "방법론",
      items: [
        "지도 수요는 고른 수단, 노선, 요일 유형, 시간대의 승차 수요를 합한 값입니다.",
        "행정동 필터는 지도에 보이는 지점이 고른 행정동 경계 안에 있는지로 계산합니다.",
        "노선 분석은 대표일 OD를 씁니다. 요일 유형(평일, 토요일, 일요일·공휴일)마다 최근 12개월 보정 TOPIS 노선 OD에서 승차·하차 정류소 쌍의 하루 평균을 냅니다. 특수일은 뺍니다. 하루씩 빼고 나머지로 맞혀 보면 평일 기준 정류소 단위 약 6%, 노선 단위 약 3% 안에 들고, 정류소 쌍 하나는 더 흔들립니다(약 26%).",
        "기간을 고르면 그 달들에 공개된 같은 요일 유형의 날을 평균합니다(BigQuery). TOPIS 노선 OD는 요일 유형마다 한 달에 하루 안팎이라, 짧은 기간은 표본 날이 적습니다. 화면에 표본 날짜를 함께 보여 줍니다.",
        "시간대를 고르면 승차 정류소의 시간대 비율(34개월 승하차 시간대 자료)로 하루 OD를 나눈 추정값을 씁니다. 그 시간에 탄 사람만 세며, 평일·토요일·일요일·공휴일 모두 됩니다. 범례 고정을 켜면 도로 위 색은 하루 중 가장 붐빈 한 시간을 기준으로 고정됩니다.",
        "시간대 배분은 한 정류소에서 탄 사람이 행선지와 상관없이 같은 시간대 모양을 따른다고 가정합니다. 이 가정으로 하차 시간대를 다시 만들어(정류소당 1.5분 이동) 실제 시간대 하차와 비교하면 오차(WAPE)가 노선×시간 7.6%, 정류소×시간 21.7%입니다. 같은 방식으로 본 승차 쪽은 6.9%, 17.3%라, 가정 때문에 더해지는 오차는 노선 단위 약 1%p, 정류소 단위 약 4%p입니다(2025-10~2026-09, 버스 658개 노선).",
        "재차 인원은 하루 동안 그 정류소를 지나간 사람의 합이지 버스 한 대의 혼잡도가 아닙니다. 혼잡도는 배차와 차량 정원을 함께 봐야 하며 앞으로 붙일 계획입니다."
      ]
    }
  },
  en: {
    ...mapMessages.en,
    app: {
      name: "Seoul Transit Demand",
      skip: "Skip to content",
      menuOpen: "Open menu",
      menuClose: "Close menu",
      language: "Language"
    },
    nav: {
      label: "Main menu",
      map: "Demand map",
      route: "Route analysis",
      method: "Methodology",
      download: "Download data",
      soon: "Coming soon"
    },
    busType: {
      trunk: "Trunk bus",
      branch: "Branch bus",
      wide: "Wide-area bus",
      circular: "Circular bus",
      village: "Village bus",
      night: "Night bus",
      ferry: "Han River bus",
      other: "Bus"
    },
    dayType: {
      label: "Day type",
      weekday: "Weekday",
      saturday: "Saturday",
      sunday_holiday: "Sunday & holiday"
    },
    busType: {
      trunk: "Trunk bus",
      branch: "Branch bus",
      express: "Express bus",
      circular: "Circular bus",
      village: "Village bus",
      night: "Night bus",
      ferry: "Han River bus",
      other: "Other route"
    },
    route: {
      title: "Route analysis",
      searchLabel: "Route number",
      searchPlaceholder: "e.g. 302",
      submit: "Analyze",
      loading: "Loading…",
      failed: "Could not load",
      enterRoute: "Enter a bus route number, for example 302 or 452.",
      showBoundaries: "Show district boundaries",
      notFound: "No OD for route {route} on this day type.",
      notFoundHour: "No OD for route {route} in this hour.",
      notFoundBasis: "No OD for route {route} in these months or hours.",
      summary: "{total} riders a day · {stops} stops · mean of {days} sample days",
      summaryHour: "{total} riders boarding {hour} · {stops} stops",
      summaryPeriod: "{total} riders a day · {stops} stops · mean of {days} sample days in {period}",
      unordered: "{value} riders could not be placed on the route",
      empty: "Enter a route number to see how many riders are on board as the bus leaves each stop.",
      dong: {
        title: "District OD",
        show: "Show districts",
        hide: "Hide",
        loading: "Calculating district OD…",
        error: "Could not load district OD.",
        meaning: "Counts this route's riders only, grouped by the district each stop is in.",
        unplaced: "{value} riders could not be placed in a district",
        pick: "Click a district on the map to see where its riders go.",
        clear: "Clear",
        totals: "{on} board and {off} alight on this route",
        direction: "Direction",
        from: "Where riders from here got off",
        to: "Where riders to here got on",
        inside: "same district"
      },
      map: {
        empty: "Analyze a route to draw it on the map.",
        loading: "Loading stop locations…",
        error: "Could not load stop locations. The analysis still works.",
        none: "No stop locations found for this route.",
        placed: "{placed} of {all} stops drawn. Click a stop on the map to look at it.",
        road: "The route follows its road path from Seoul's bus route data.",
        straight: "No road path was available, so stops are joined by straight lines."
      },
      hour: {
        label: "Time of day",
        allDay: "Whole day",
        pick: "Pick an hour",
        weekdayOnly: "Hourly values exist for weekdays only. Saturday, Sunday and holidays show the whole day.",
        meaning: "Counts only riders who boarded in that hour.",
        range: "{from}:00–{to}:00"
      },
      chart: {
        title: "Riders on board as the bus leaves each stop",
        aria: "Bar chart of riders on board after each stop",
        hint: "Click a bar to look at that stop.",
        up: "Upbound",
        down: "Downbound",
        turnaround: "Turns at stop {seq} (estimated)",
        turnaroundBis: "Turns at stop {seq}",
        bar: "{seq} {name}: {onBoard} on board leaving, {on} on, {off} off",
        max: "Peak {value}"
      },
      stop: {
        heading: "Stop {seq}",
        alight: "Got off",
        onBoard: "On board leaving",
        board: "Got on",
        destinations: "Riders boarding here got off at",
        origins: "Riders alighting here got on at",
        none: "None"
      },
      reach: {
        heading: "How far the riders on board leaving this stop travel",
        label: "Threshold",
        input: "Enter threshold",
        unit: "riders",
        result: "Last stop with more than {value} still on board",
        more: "{count} more stops",
        never: "Fewer than {value} on board from the start.",
        invalid: "Enter a number of 1 or more."
      },
      segment: {
        heading: "Section analysis",
        from: "From",
        to: "To",
        submit: "Show section",
        title: "{from} {fromName} to {to} {toName}",
        within: "Got on and off inside",
        leaving: "Got on inside, off after",
        entering: "Got on before, off inside",
        through: "Rode through",
        share: "{value} of the route",
        leavingTo: "Riders leaving the section got off at",
        enteringFrom: "Riders entering the section got on at"
      },
      table: {
        summary: "Stop table ({count} stops)",
        seq: "Seq",
        name: "Stop",
        ars: "ARS",
        on: "On",
        off: "Off",
        onBoard: "On board leaving"
      },
      source:
        "Calculated from corrected Seoul TOPIS boarding and alighting OD. TOPIS publishes three days of OD a month; each value is the mean over the published days of this type in the last twelve months (usually 10 for weekdays), special days left out."
    },
    method: {
      title: "Methodology",
      items: [
        "The demand map sums boarding demand for the chosen mode, routes, day type and hours.",
        "The district filter checks whether each visible point falls inside the chosen district boundaries.",
        "Route analysis uses typical-day OD: for each day type (weekday, Saturday, Sunday and public holiday), the mean daily passengers of every boarding-to-alighting stop pair over the last twelve months of corrected TOPIS route OD, special days left out. Predicting each day from the others, it is within about 6% by stop and 3% by route on weekdays; single stop pairs are noisier (about 26%).",
        "With months chosen, the mean is taken over the published days of this type in those months (BigQuery). TOPIS route OD has about one day of each type a month, so a short period rests on few days; the sample days are shown with it.",
        "With hours chosen, the daily OD is split by the boarding stop's hourly profile (34 months of hourly boarding counts). Only riders who boarded in those hours are counted, on any day type. With the fixed scale on, the road colours are fixed to the busiest hour of the day.",
        "The hourly split assumes riders boarding at a stop share one time profile whatever their destination. Rebuilding hourly alightings from it (1.5 minutes a stop) and comparing them with counted hourly alightings gives a WAPE of 7.6% by route and hour and 21.7% by stop and hour; boardings checked the same way give 6.9% and 17.3%, so the assumption adds about 1 point by route and 4 points by stop (2025-10 to 2026-09, 658 bus routes).",
        "On-board figures are daily totals passing each stop, not the load of one bus. Crowding needs service frequency and vehicle capacity as well and is planned for later."
      ]
    }
  }
};
