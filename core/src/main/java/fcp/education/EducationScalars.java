package fcp.education;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

/** Lossless boundary helpers only. No host identity, authorization, persistence or SQL. */
public final class EducationScalars {
    private EducationScalars() {}
    private static final BigInteger MAX = new BigInteger("18446744073709551615");
    private static final LocalDateTime LAST = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 499999000);

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }

    public static String text(Object input, int maximum) {
        if (!(input instanceof String value) || value.isEmpty()
                || value.codePointCount(0, value.length()) > maximum) {
            throw invalid("Expected nonempty text within character limit");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw invalid("Invalid Unicode scalar value");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw invalid("Invalid Unicode scalar value");
            }
        }
        return value;
    }

    /** Preserve the original trusted-host tenant exactly; validation cannot establish provenance. */
    public static String tenantId(Object input) {
        return text(input, 100);
    }

    public static String unsignedBigint(Object input, boolean positive) {
        if (!(input instanceof String value) || value.length() > 20 || !value.matches("0|[1-9][0-9]*")) {
            throw invalid("Expected canonical unsigned BIGINT decimal string");
        }
        BigInteger number = new BigInteger(value);
        if (number.compareTo(MAX) > 0 || (positive && number.signum() == 0)) {
            throw invalid("Unsigned BIGINT outside allowed range");
        }
        return value;
    }

    public static String decimalString(Object input, int precision, int scale, Integer unitScale) {
        if (precision < 1 || precision > 65 || scale < 0 || scale > Math.min(30, precision)) {
            throw invalid("Invalid DECIMAL declaration");
        }
        int allowed = scale;
        if (unitScale != null) {
            if (unitScale < 0 || unitScale > Math.min(4, scale)) throw invalid("Invalid account unit scale");
            allowed = unitScale;
        }
        if (!(input instanceof String value) || value.length() > precision + 2
                || !value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) {
            throw invalid("Expected exact decimal string");
        }
        String magnitude = value.startsWith("-") ? value.substring(1) : value;
        String[] parts = magnitude.split("\\.", -1);
        int integerDigits = parts[0].equals("0") ? 0 : parts[0].length();
        if (integerDigits > precision - scale || (parts.length == 2 && parts[1].length() > allowed)) {
            throw invalid("DECIMAL overflow or excess fraction; rounding forbidden");
        }
        return value;
    }

    public static String utcTimestamp(Object input) {
        if (!(input instanceof String value) || !value.matches(
                "[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z")) {
            throw invalid("Expected UTC timestamp with at most six fraction digits");
        }
        try {
            if (LocalDateTime.parse(value.substring(0, value.length() - 1)).isAfter(LAST)) {
                throw invalid("Outside project DATETIME persistence range");
            }
        } catch (DateTimeParseException e) {
            throw invalid("Invalid UTC timestamp");
        }
        return value;
    }

    /** Business calendar date; school timezone resolution is a separate trusted-host concern. */
    public static String businessDate(Object input) {
        if (!(input instanceof String value) || !value.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}")) {
            throw invalid("Expected business date YYYY-MM-DD");
        }
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid("Invalid business date");
        }
        return value;
    }

    public static String nextVersion(Object expected) {
        BigInteger value = new BigInteger(unsignedBigint(expected, false));
        if (value.equals(MAX)) throw invalid("Version exhausted");
        return value.add(BigInteger.ONE).toString();
    }

    /** Structural check only. Names/types must come from a verified server-side catalog. */
    public static Map<String, String> compositeKey(Map<String, String> key, Set<String> reviewedColumns,
                                                   String trustedTenant) {
        tenantId(trustedTenant);
        if (key == null || reviewedColumns == null || !reviewedColumns.contains("tenant_id")
                || !key.keySet().equals(reviewedColumns) || !trustedTenant.equals(key.get("tenant_id"))) {
            throw invalid("Exactly the full same-tenant primary key is required");
        }
        for (String value : key.values()) if (value == null) throw invalid("Null key component");
        return Map.copyOf(key);
    }

    /** Must be called within the domain transaction; failure must roll it back. */
    public static void requireSingleAffectedRow(long rows) {
        if (rows != 1) throw invalid("CAS conflict or unexpected affected-row count");
    }
}
