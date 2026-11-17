/** Seoul's 25 구 by statistics code (first five digits of a 행정동 code). */
export const SEOUL_GU = {
  11010: ["종로구", "Jongno-gu"], 11020: ["중구", "Jung-gu"], 11030: ["용산구", "Yongsan-gu"],
  11040: ["성동구", "Seongdong-gu"], 11050: ["광진구", "Gwangjin-gu"], 11060: ["동대문구", "Dongdaemun-gu"],
  11070: ["중랑구", "Jungnang-gu"], 11080: ["성북구", "Seongbuk-gu"], 11090: ["강북구", "Gangbuk-gu"],
  11100: ["도봉구", "Dobong-gu"], 11110: ["노원구", "Nowon-gu"], 11120: ["은평구", "Eunpyeong-gu"],
  11130: ["서대문구", "Seodaemun-gu"], 11140: ["마포구", "Mapo-gu"], 11150: ["양천구", "Yangcheon-gu"],
  11160: ["강서구", "Gangseo-gu"], 11170: ["구로구", "Guro-gu"], 11180: ["금천구", "Geumcheon-gu"],
  11190: ["영등포구", "Yeongdeungpo-gu"], 11200: ["동작구", "Dongjak-gu"], 11210: ["관악구", "Gwanak-gu"],
  11220: ["서초구", "Seocho-gu"], 11230: ["강남구", "Gangnam-gu"], 11240: ["송파구", "Songpa-gu"],
  11250: ["강동구", "Gangdong-gu"]
};

/** 구 name in the interface language, or null for a code outside Seoul. */
export function guName(code, lang) {
  const entry = SEOUL_GU[String(code).slice(0, 5)];
  if (!entry) return null;
  return lang === "en" ? entry[1] : entry[0];
}
