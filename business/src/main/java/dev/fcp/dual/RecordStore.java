package dev.fcp.dual;

import java.util.*;

/** The main container owns persistence and atomic idempotency; plugins never receive a connection. */
public interface RecordStore extends AutoCloseable {
    Map<String,Object> get(String scope, String kind, String id);
    List<Object> list(String scope, String kind);
    List<Object> history(String scope, String kind, String id);
    Map<String,Object> replay(String scope, String subject, String requestKey, String fingerprint);
    Map<String,Object> commit(String scope, String kind, String subject, String requestKey,
                              String fingerprint, String id, long expectedVersion,
                              Map<String,Object> body, Map<String,Object> audit);
    /** Host-side class scope filtering; override to push pagination into a real database. */
    default List<Object> page(String scope,String kind,Set<String> classes,boolean allClasses,String afterId,int limit) {
        if(limit<1||limit>1001||classes.size()>32)throw new IllegalArgumentException("Invalid page bounds");
        return list(scope,kind).stream().filter(v->{
            Map<String,Object> row=fcp.Schema.map(v);
            return fcp.Schema.str(row.get("id")).compareTo(afterId)>0 &&
                (allClasses||classes.contains(fcp.Schema.obj(row,"body").get("classId")));
        }).sorted(Comparator.comparing(v->fcp.Schema.str(fcp.Schema.map(v).get("id")))).limit(limit).toList();
    }
    default void close() {}
}
