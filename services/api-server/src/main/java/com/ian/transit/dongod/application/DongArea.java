package com.ian.transit.dongod.application;

import java.util.Map;

/**
 * Grouping level for district OD: one 행정동, one 구, or all of Seoul.
 *
 * Codes: a 행정동 is its 8-digit adm_cd; a 구 is the first five digits
 * (11010 = 종로구); Seoul is "11". Everywhere outside Seoul stays 'outside' at
 * every level.
 */
public enum DongArea {
    DONG, GU, CITY;

    public static final String SEOUL = "11";
    public static final String OUTSIDE = "outside";

    /** Seoul's 25 구 by statistics code. */
    public static final Map<String, String> GU_NAMES = Map.ofEntries(
            Map.entry("11010", "종로구"), Map.entry("11020", "중구"), Map.entry("11030", "용산구"),
            Map.entry("11040", "성동구"), Map.entry("11050", "광진구"), Map.entry("11060", "동대문구"),
            Map.entry("11070", "중랑구"), Map.entry("11080", "성북구"), Map.entry("11090", "강북구"),
            Map.entry("11100", "도봉구"), Map.entry("11110", "노원구"), Map.entry("11120", "은평구"),
            Map.entry("11130", "서대문구"), Map.entry("11140", "마포구"), Map.entry("11150", "양천구"),
            Map.entry("11160", "강서구"), Map.entry("11170", "구로구"), Map.entry("11180", "금천구"),
            Map.entry("11190", "영등포구"), Map.entry("11200", "동작구"), Map.entry("11210", "관악구"),
            Map.entry("11220", "서초구"), Map.entry("11230", "강남구"), Map.entry("11240", "송파구"),
            Map.entry("11250", "강동구"));

    public static DongArea parse(String value, DongArea fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase()) {
            case "dong" -> DONG;
            case "gu" -> GU;
            case "city" -> CITY;
            default -> throw new IllegalArgumentException("level must be dong, gu or city, got " + value);
        };
    }

    /** Throws when the code does not fit the level. */
    public void requireCode(String code) {
        boolean ok = switch (this) {
            case DONG -> code != null && code.matches("11\\d{6}");
            case GU -> code != null && GU_NAMES.containsKey(code);
            case CITY -> SEOUL.equals(code);
        };
        if (!ok) {
            throw new IllegalArgumentException("code " + code + " is not a " + name().toLowerCase() + " code");
        }
    }

    /** SQL expression giving the unit code of a district code column. */
    public String unitSql(String column) {
        requireColumn(column);
        return switch (this) {
            case DONG -> column;
            case GU -> "CASE WHEN " + column + " = 'outside' THEN 'outside' ELSE LEFT(" + column + ", 5) END";
            case CITY -> "CASE WHEN " + column + " = 'outside' THEN 'outside' ELSE '11' END";
        };
    }

    /**
     * SQL condition that a district code column falls in the unit :code.
     * Written as a range so the primary key (day_type, origin_cd, …) is used.
     */
    public String filterSql(String column) {
        requireColumn(column);
        return switch (this) {
            case DONG -> column + " = :code";
            case GU -> column + " >= :code AND " + column + " < :codeEnd";
            case CITY -> column + " <> 'outside'";
        };
    }

    /** Upper bound for the GU range: the next 5-digit code. */
    public static String codeEnd(String code) {
        return String.valueOf(Long.parseLong(code) + 1);
    }

    /** Display name for a unit code at this level; dong names come from the table. */
    public String nameOf(String code, String dongName) {
        if (OUTSIDE.equals(code)) {
            return OUTSIDE;
        }
        return switch (this) {
            case DONG -> dongName;
            case GU -> GU_NAMES.getOrDefault(code, code);
            case CITY -> "서울";
        };
    }

    /**
     * Name of a 행정동 anywhere in the capital area, told apart from a Seoul one
     * of the same name: outside Seoul the province goes in front (위례동 is in
     * 송파구 and also in 경기). Seoul names stay as they are.
     */
    public static String placeName(String admCd, String dongName) {
        if (admCd == null || dongName == null) {
            return dongName;
        }
        if (admCd.startsWith("23")) {
            return "인천 " + dongName;
        }
        if (admCd.startsWith("31")) {
            return "경기 " + dongName;
        }
        return dongName;
    }

    /**
     * unitSql and filterSql put the column name into SQL text, so it must be a
     * plain identifier written in the code (origin_cd, dest_cd, adm_cd), never
     * anything from a request. Values always go in as :code / :codeEnd.
     */
    static void requireColumn(String column) {
        if (column == null || !column.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("not a column name: " + column);
        }
    }
}
