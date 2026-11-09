package fcp.education;

import fcp.FcpException;
import java.sql.*;
import java.time.Instant;
import java.util.Set;

/** Typed identity-owner resolver. Package-private and usable only within admitted roster reads.
 * It returns no identity attributes. PENDING policy is not an ACTIVE-person visibility policy.
 * Person has no school_id or business-valid interval. Temporal context comes from the locked
 * enrollment/assignment and, for membership, its same-school enrollment. created_at is not validity.
 */
final class RosterPersonResolver {
    private RosterPersonResolver() {}
    private static void invalid(){throw new FcpException("EDUCATION_INVALID_RELATION","Invalid typed person reference");}
    static void validateInterval(String from,String to) {
        EducationScalars.utcTimestamp(from);if(to!=null)EducationScalars.utcTimestamp(to);
        if(to!=null&&!Instant.parse(to).isAfter(Instant.parse(from)))invalid();
    }
    static void validateWithin(String from,String to,String outerFrom,String outerTo) {
        validateInterval(from,to);validateInterval(outerFrom,outerTo);
        if(Instant.parse(from).isBefore(Instant.parse(outerFrom))||outerTo!=null&&(to==null||Instant.parse(to).isAfter(Instant.parse(outerTo))))invalid();
    }
    static void verify(Connection connection,String tenant,String person,String school,String kind,
                       String from,String to,String asOf,boolean currentSchoolAuthority)throws SQLException {
        // This connection is the caller's already-admitted transaction; never open another source.
        if(connection.getAutoCommit()||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED||!currentSchoolAuthority)invalid();
        EducationScalars.tenantId(tenant);EducationScalars.unsignedBigint(person,true);EducationScalars.unsignedBigint(school,true);
        RosterStore.operation(kind);validateInterval(from,to);EducationScalars.utcTimestamp(asOf);
        // Validate the left-closed/right-open temporal context, without treating history as current authority.
        temporalContext(from,to,asOf);
        try(PreparedStatement q=connection.prepareStatement("SELECT id,status FROM ed_identity_person WHERE tenant_id=? AND id=CAST(? AS UNSIGNED) FOR SHARE")) {
            q.setQueryTimeout(10);q.setString(1,tenant);q.setString(2,person);
            try(ResultSet r=q.executeQuery()) {
                if(!r.next()||!person.equals(r.getString(1)))invalid();
                RosterStore.known(r.getString(2),Set.of("ACTIVE","LEFT","RESTRICTED"));
            }
        }
    }
    static String temporalContext(String from,String to,String at) {
        validateInterval(from,to);EducationScalars.utcTimestamp(at);Instant instant=Instant.parse(at);
        return instant.isBefore(Instant.parse(from))?"FUTURE":to!=null&&!instant.isBefore(Instant.parse(to))?"HISTORICAL":"CURRENT";
    }
}
