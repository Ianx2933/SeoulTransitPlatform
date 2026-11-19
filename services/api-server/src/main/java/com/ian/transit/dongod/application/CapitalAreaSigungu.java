package com.ian.transit.dongod.application;

import java.util.Map;

/**
 * 시·군 (Gyeonggi) and 구·군 (Incheon) of a capital-area 행정동 code outside
 * Seoul, from the statistics codes admin_dong_boundary uses (23 = 인천,
 * 31 = 경기). admin_dong_boundary has dong names only, no 시군구 name.
 *
 * A few 시·군 of 강원·충북·충남 at the edge of the boundary table are listed too.
 * 군 codes run from 50 (2351 강화군, 3155 연천군), not from 30.
 *
 * Keyed by the first four digits: a Gyeonggi city's general 구 share them
 * (31191 처인구, 31192 기흥구, 31193 수지구 are all 3119 = 용인시), so riders are
 * counted by city. Checked against the dong names in admin_dong_boundary with
 * ops/checks/check_sigungu_codes.sql (2026-11-19: every key matched). Incheon names are those before the
 * 2026-07 reorganisation, matching the boundaries (base_date 20250630).
 */
public final class CapitalAreaSigungu {

    private CapitalAreaSigungu() {
    }

    public record Name(String ko, String en) {
    }

    private static Map.Entry<String, Name> e(String key, String ko, String en) {
        return Map.entry(key, new Name(ko, en));
    }

    static final Map<String, Name> NAMES = Map.ofEntries(
            e("2301", "인천 중구", "Incheon Jung-gu"),
            e("2302", "인천 동구", "Incheon Dong-gu"),
            e("2309", "인천 미추홀구", "Incheon Michuhol-gu"),
            e("2304", "인천 연수구", "Incheon Yeonsu-gu"),
            e("2305", "인천 남동구", "Incheon Namdong-gu"),
            e("2306", "인천 부평구", "Incheon Bupyeong-gu"),
            e("2307", "인천 계양구", "Incheon Gyeyang-gu"),
            e("2308", "인천 서구", "Incheon Seo-gu"),
            e("2351", "인천 강화군", "Incheon Ganghwa-gun"),
            e("2352", "인천 옹진군", "Incheon Ongjin-gun"),
            e("3101", "경기 수원시", "Suwon"),
            e("3102", "경기 성남시", "Seongnam"),
            e("3103", "경기 의정부시", "Uijeongbu"),
            e("3104", "경기 안양시", "Anyang"),
            e("3105", "경기 부천시", "Bucheon"),
            e("3106", "경기 광명시", "Gwangmyeong"),
            e("3107", "경기 평택시", "Pyeongtaek"),
            e("3108", "경기 동두천시", "Dongducheon"),
            e("3109", "경기 안산시", "Ansan"),
            e("3110", "경기 고양시", "Goyang"),
            e("3111", "경기 과천시", "Gwacheon"),
            e("3112", "경기 구리시", "Guri"),
            e("3113", "경기 남양주시", "Namyangju"),
            e("3114", "경기 오산시", "Osan"),
            e("3115", "경기 시흥시", "Siheung"),
            e("3116", "경기 군포시", "Gunpo"),
            e("3117", "경기 의왕시", "Uiwang"),
            e("3118", "경기 하남시", "Hanam"),
            e("3119", "경기 용인시", "Yongin"),
            e("3120", "경기 파주시", "Paju"),
            e("3121", "경기 이천시", "Icheon"),
            e("3122", "경기 안성시", "Anseong"),
            e("3123", "경기 김포시", "Gimpo"),
            e("3124", "경기 화성시", "Hwaseong"),
            e("3125", "경기 광주시", "Gwangju (Gyeonggi)"),
            e("3126", "경기 양주시", "Yangju"),
            e("3127", "경기 포천시", "Pocheon"),
            e("3128", "경기 여주시", "Yeoju"),
            e("3155", "경기 연천군", "Yeoncheon"),
            e("3157", "경기 가평군", "Gapyeong"),
            e("3158", "경기 양평군", "Yangpyeong"),
            // Edges of the neighbouring provinces that admin_dong_boundary also holds
            e("3201", "강원 춘천시", "Chuncheon"),
            e("3251", "강원 홍천군", "Hongcheon"),
            e("3355", "충북 진천군", "Jincheon"),
            e("3357", "충북 음성군", "Eumseong"),
            e("3401", "충남 천안시", "Cheonan"),
            e("3404", "충남 아산시", "Asan"));

    /** The four-digit key of a 행정동 code, or null for a short or missing code. */
    public static String key(String admCd) {
        return admCd == null || admCd.length() < 4 ? null : admCd.substring(0, 4);
    }

    /** Name of a key, or null when the key is not listed (then the client shows the code). */
    public static Name name(String key) {
        return key == null ? null : NAMES.get(key);
    }
}
