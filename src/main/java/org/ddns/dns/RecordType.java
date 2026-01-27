package org.ddns.dns;

public final class RecordType {
    public static final int A = 1;
    public static final int NS = 2;
    public static final int CNAME = 5;
    public static final int SOA = 6;
    public static final int PTR = 12;
    public static final int MX = 15;
    public static final int TXT = 16;
    public static final int AAAA = 28;

    public static String toString(int type) {
        return switch (type) {
            case A -> "A";
            case AAAA -> "AAAA";
            case MX -> "MX";
            case NS -> "NS";
            case PTR -> "PTR";
            case TXT -> "TXT";
            case CNAME -> "CNAME";
            case SOA -> "SOA";
            default -> "UNKNOWN(" + type + ")";
        };
    }

    public static int fromString(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("Record type string is null or empty");
        }

        String t = s.trim().toUpperCase();

        // Allow numeric types like "1", "28"
        if (t.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(t);
        }

        return switch (t) {
            case "A" -> A;
            case "AAAA" -> AAAA;
            case "MX" -> MX;
            case "NS" -> NS;
            case "PTR" -> PTR;
            case "TXT" -> TXT;
            case "CNAME" -> CNAME;
            case "SOA" -> SOA;
            default -> throw new IllegalArgumentException("Unknown DNS record type: " + s);
        };
    }
}
