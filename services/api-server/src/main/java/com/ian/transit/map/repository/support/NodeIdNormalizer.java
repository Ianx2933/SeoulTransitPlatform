package com.ian.transit.map.repository.support;

/**
 * Normalizes stop and station node identifiers for comparison and grouping.
 *
 * Implementation note: numeric IDs are padded with leading zeroes using string
 * operations rather than {@code Integer.parseInt}. The previous integer-based
 * implementation threw {@code NumberFormatException} when the input exceeded
 * {@code Integer.MAX_VALUE} (10 digits). Since stop IDs are identifiers and
 * never need arithmetic, string padding is both safer and semantically correct.
 */
public final class NodeIdNormalizer {

    private static final int ARS_WIDTH = 5;

    private NodeIdNormalizer() {
    }

    /**
     * Normalizes numeric bus-style node IDs to five digits.
     *
     * Numeric IDs shorter than 5 digits are left-padded with zeroes. IDs that
     * are already 5 or more digits are returned unchanged. Non-numeric IDs
     * (subway station names, alphanumeric codes) are returned trimmed.
     */
    public static String normalize(String nodeId) {
        if (nodeId == null) {
            return "";
        }

        String trimmed = nodeId.trim();

        if (trimmed.isEmpty()) {
            return "";
        }

        if (isAllDigits(trimmed) && trimmed.length() < ARS_WIDTH) {
            return "0".repeat(ARS_WIDTH - trimmed.length()) + trimmed;
        }

        return trimmed;
    }

    /**
     * Returns true when every character is an ASCII digit.
     */
    private static boolean isAllDigits(String value) {
        for (int index = 0; index < value.length(); index += 1) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
