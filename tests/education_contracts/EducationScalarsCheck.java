import fcp.education.EducationScalars;
import java.util.Map;
import java.util.Set;

public final class EducationScalarsCheck {
    static int checks;
    static void same(String actual, String expected) {
        checks++;
        if (!actual.equals(expected)) throw new AssertionError("Value changed");
    }
    static void rejects(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException e) { return; }
        throw new AssertionError("Invalid value accepted");
    }
    public static void main(String[] args) {
        for (String value : new String[]{" tenant ", "é", "e\u0301", "😀".repeat(100), " "})
            same(EducationScalars.tenantId(value), value);
        for (Object value : new Object[]{"", "😀".repeat(101), "\ud800", "\udc00", null, 1, true})
            rejects(() -> EducationScalars.tenantId(value));
        for (String value : new String[]{"0", "9007199254740993", "18446744073709551615"})
            same(EducationScalars.unsignedBigint(value, false), value);
        for (Object value : new Object[]{"18446744073709551616", "01", "-1", "+1", "1e3", "1\n", "１", 1, 1.0, true, null})
            rejects(() -> EducationScalars.unsignedBigint(value, false));
        rejects(() -> EducationScalars.unsignedBigint("0", true));
        same(EducationScalars.unsignedBigint("1", true), "1");
        for (String value : new String[]{"0", "-0.0000", "99999999.9999", "-99999999.9999", "0.1000"})
            same(EducationScalars.decimalString(value, 12, 4, null), value);
        same(EducationScalars.decimalString("999999999999.9999", 16, 4, null), "999999999999.9999");
        for (Object value : new Object[]{0.1, 1, true, null, "NaN", "Infinity", "1e1", "1.00000", "100000000", "+1", ".1", "1.", "01", " 1", "1\n"})
            rejects(() -> EducationScalars.decimalString(value, 12, 4, null));
        rejects(() -> EducationScalars.decimalString("10.120", 16, 4, 2));
        rejects(() -> EducationScalars.decimalString("10.1", 16, 4, 0));
        rejects(() -> EducationScalars.decimalString("1", 16, 4, 5));
        rejects(() -> EducationScalars.decimalString("1", 66, 4, null));
        same(EducationScalars.decimalString("10.12", 16, 4, 2), "10.12");
        for (String value : new String[]{"1000-01-01T00:00:00Z", "2024-02-29T23:59:59.123456Z", "9999-12-31T23:59:59.499999Z"})
            same(EducationScalars.utcTimestamp(value), value);
        for (String value : new String[]{"0999-12-31T23:59:59Z", "9999-12-31T23:59:59.500000Z", "2025-02-29T00:00:00Z", "2026-01-01T00:00:00.1234567Z", "2026-01-01T00:00:00+00:00", "2026-01-01 00:00:00Z", "2026-01-01T00:00:60Z", "2026-01-01"})
            rejects(() -> EducationScalars.utcTimestamp(value));
        same(EducationScalars.businessDate("2024-02-29"), "2024-02-29");
        for (String value : new String[]{"2025-02-29", "2026-1-01", "2026-01-01T00:00:00Z", "0999-01-01"})
            rejects(() -> EducationScalars.businessDate(value));
        same(EducationScalars.nextVersion("0"), "1");
        same(EducationScalars.nextVersion("9223372036854775807"), "9223372036854775808");
        rejects(() -> EducationScalars.nextVersion("18446744073709551615"));
        var key = Map.of("tenant_id", " t ", "id", "1");
        if (!EducationScalars.compositeKey(key, Set.of("tenant_id", "id"), " t ").equals(key))
            throw new AssertionError("Key changed");
        checks++;
        rejects(() -> EducationScalars.compositeKey(key, Set.of("tenant_id", "id"), "t"));
        rejects(() -> EducationScalars.compositeKey(key, Set.of("id"), " t "));
        rejects(() -> EducationScalars.compositeKey(key, Set.of("tenant_id", "id", "scope"), " t "));
        EducationScalars.requireSingleAffectedRow(1); checks++;
        for (long rows : new long[]{0, 2, -1}) rejects(() -> EducationScalars.requireSingleAffectedRow(rows));
        System.out.println("PASS_JAVA_BOUNDARY_ONLY checks=" + checks);
    }
}
