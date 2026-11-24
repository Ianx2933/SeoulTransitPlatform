/**
 * About and privacy pages, in Korean and English. Spread into messages.js.
 * Each privacy section is { title, body }; both languages keep the same keys.
 */
export const PRIVACY_SECTIONS = ["collect", "logs", "storage", "thirdParty", "rights", "officer", "changes"];

export const aboutMessages = {
  ko: {
    about: {
      title: "안내",
      intro:
        "CityRiderMap은 서울 버스·지하철 이용 자료로 정류소·노선·행정동 단위의 수요를 보여 주는 사이트입니다. 시민, 시민단체, 연구자가 자유롭게 쓸 수 있도록 만들었습니다. 서울특별시 공식 서비스가 아니며, 화면의 값에는 추정이 들어 있습니다.",
      operator: "운영 {name}",
      useTitle: "쓰는 법",
      useItems: [
        "지도 수요: 수단, 노선, 요일 유형, 시간대를 고르고 불러오면 정류소·역별 승차 수요가 지도에 나옵니다. 행정동으로 범위를 좁힐 수 있습니다.",
        "노선 분석: 버스 노선을 고르면 대표일 정류소 간 OD, 재차 인원, 도로 위 부하를 봅니다. 기간(달)과 시간대를 바꿔 볼 수 있습니다.",
        "데이터 내려받기: 화면에 쓰는 표를 CSV로 내려받거나 BigQuery에서 바로 조회할 수 있습니다.",
        "방법론: 값을 어떻게 계산했는지와 정확도를 적어 두었습니다. 추정값을 인용할 때 함께 봐 주세요."
      ],
      sourceTitle: "데이터 출처",
      sourceItems: [
        "서울특별시 대중교통 이용 자료: 서울 열린데이터광장, 공공데이터포털, 서울 TOPIS (공공누리 제1유형: 출처표시)",
        "지도 배경: © OpenStreetMap contributors (ODbL)"
      ],
      reuseTitle: "이 사이트의 자료를 쓸 때",
      reuseText:
        "출처를 \"CityRiderMap (원천: 서울특별시, 공공누리 제1유형)\"처럼 밝혀 주세요. 출처를 밝히면 상업적 이용과 변경도 됩니다. 서울특별시나 이 사이트가 결과를 보증하는 것처럼 쓰지는 말아 주세요.",
      reportTitle: "오류 신고·문의",
      reportText:
        "값이 이상하거나 화면이 동작하지 않으면 알려 주세요. 노선 번호, 요일 유형, 기간·시간대와 화면 주소를 함께 적어 주시면 빨리 확인할 수 있습니다.",
      reportLink: "GitHub 이슈로 알리기",
      email: "이메일 {email}",
      privacyLink: "개인정보처리방침"
    },
    privacy: {
      title: "개인정보처리방침",
      effective: "시행일 {date}",
      intro: "CityRiderMap(운영 {name})이 이용자 정보를 어떻게 다루는지 적은 문서입니다.",
      sections: {
        collect: {
          title: "1. 받는 개인정보",
          body: "회원가입, 로그인, 입력 양식이 없고 이름이나 연락처를 받지 않습니다. 방문 분석이나 광고용 쿠키도 쓰지 않습니다."
        },
        logs: {
          title: "2. 자동으로 남는 정보",
          body: "사이트와 데이터 파일은 Google 인프라(Firebase Hosting, Cloud Run, Cloud Storage)에서 제공됩니다. 요청을 처리하면서 IP 주소, 요청 시각, 요청 주소, 브라우저 정보가 서버 로그에 남을 수 있습니다. 운영자는 이를 장애 대응과 남용 방지에만 쓰며, 운영자가 보는 로그는 Google Cloud Logging 기본 보존 기간(30일)이 지나면 지워집니다."
        },
        storage: {
          title: "3. 브라우저 저장소",
          body: "언어 선택과 일부 화면 설정(도로 부하 최소값, 노선 분석 창을 접었는지)을 브라우저의 localStorage에 저장합니다. 이 값은 이용자의 브라우저에만 있고 운영자에게 보내지지 않으며, 브라우저 설정에서 지울 수 있습니다."
        },
        thirdParty: {
          title: "4. 제3자 제공과 외부 서비스",
          body: "개인정보를 제3자에게 제공하지 않습니다. 지도 배경은 OpenStreetMap 타일 서버에서 받아 오므로, 지도를 열면 브라우저가 그 서버에 직접 접속하고 IP 주소 등은 OpenStreetMap 재단의 정책에 따라 처리됩니다. 호스팅은 Google LLC가 맡습니다."
        },
        rights: {
          title: "5. 이용자의 권리",
          body: "운영자는 이용자를 알아볼 수 있는 정보를 따로 보관하지 않습니다. 그래도 자신의 정보에 대해 열람, 정정, 삭제를 요청하고 싶다면 아래 연락처로 알려 주세요. 확인한 뒤 답하겠습니다."
        },
        officer: {
          title: "6. 개인정보 보호책임자",
          body: "{name}. 연락은 아래 GitHub 이슈 또는 이메일로 받습니다."
        },
        changes: {
          title: "7. 바뀔 때",
          body: "광고(예: Google AdSense)를 붙이는 등 처리 내용이 바뀌면, 시행 전에 이 페이지를 고치고 시행일을 바꿉니다."
        }
      }
    }
  },
  en: {
    about: {
      title: "About",
      intro:
        "CityRiderMap shows bus and subway demand in Seoul by stop, route and district (행정동), built from public ridership data for citizens, civic groups and researchers to use freely. It is not an official Seoul Metropolitan Government service, and the figures include estimates.",
      operator: "Run by {name}",
      useTitle: "How to use",
      useItems: [
        "Demand map: choose modes, routes, day type and hours, then load to see boarding demand by stop and station. Districts narrow the area.",
        "Route analysis: choose a bus route to see representative-day stop-to-stop OD, on-board load and load on the road. Months and hours can be changed.",
        "Download data: the tables behind the site, as CSV or queried directly in BigQuery.",
        "Methodology: how the figures are calculated and how accurate they are; please read it before citing estimates."
      ],
      sourceTitle: "Data sources",
      sourceItems: [
        "Seoul Metropolitan Government public transport data: Seoul Open Data Plaza, data.go.kr and Seoul TOPIS (KOGL Type 1, attribution)",
        "Base map: © OpenStreetMap contributors (ODbL)"
      ],
      reuseTitle: "Using data from this site",
      reuseText:
        "Please credit it, for example \"CityRiderMap (source: Seoul Metropolitan Government, KOGL Type 1)\". With attribution, commercial use and changes are allowed. Please do not present results as endorsed by Seoul Metropolitan Government or by this site.",
      reportTitle: "Report a problem",
      reportText:
        "If a figure looks wrong or the page does not work, please tell us. The route number, day type, months and hours, and the page address help us check quickly.",
      reportLink: "Report on GitHub Issues",
      email: "Email {email}",
      privacyLink: "Privacy policy"
    },
    privacy: {
      title: "Privacy policy",
      effective: "Effective {date}",
      intro: "How CityRiderMap (run by {name}) handles information about visitors.",
      sections: {
        collect: {
          title: "1. Personal information collected",
          body: "There are no accounts, sign-ins or forms, and no names or contact details are collected. No analytics or advertising cookies are used."
        },
        logs: {
          title: "2. Information recorded automatically",
          body: "The site and data files are served from Google infrastructure (Firebase Hosting, Cloud Run, Cloud Storage). Serving a request can record the IP address, time, requested address and browser details in server logs. The operator uses them only to fix faults and prevent abuse; the logs the operator can see are deleted after Google Cloud Logging's default retention (30 days)."
        },
        storage: {
          title: "3. Browser storage",
          body: "The language choice and a few display settings (the minimum road load, whether the route analysis panels are folded) are kept in the browser's localStorage. They stay in your browser, are not sent to the operator, and can be cleared in the browser's settings."
        },
        thirdParty: {
          title: "4. Third parties and outside services",
          body: "Personal information is not given to third parties. The base map is loaded from OpenStreetMap's tile servers, so opening a map connects your browser to them directly and your IP address is handled under the OpenStreetMap Foundation's policy. Hosting is provided by Google LLC."
        },
        rights: {
          title: "5. Your rights",
          body: "The operator keeps no information that identifies visitors. If you still wish to see, correct or delete information about you, contact us below and we will check and reply."
        },
        officer: {
          title: "6. Person responsible for privacy",
          body: "{name}. Contact through GitHub Issues or email below."
        },
        changes: {
          title: "7. Changes",
          body: "If the handling changes, for example by adding advertising such as Google AdSense, this page and its effective date are updated before the change takes effect."
        }
      }
    }
  }
};
