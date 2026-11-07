import java.io.IOException;
import java.util.*;

/** Pure configuration guard tests. These do not connect to a database or claim MySQL evidence. */
public final class JournalConfigurationTest {
    static Map<String,String> valid(){return new HashMap<>(Map.of("GITHUB_ACTIONS","true","FCP_CRASH_TEST_URL","jdbc:mysql://127.0.0.1:33306/fcp_ci_crash_0123456789abcdef?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC","FCP_CRASH_TEST_USER","test-only","FCP_CRASH_TEST_PASSWORD","synthetic-not-a-real-password","FCP_CRASH_TEST_DRIVER_CLASSPATH","explicit-driver.jar"));}
    static void rejected(Map<String,String> env)throws Exception{try{TestJournals.validate(env);throw new AssertionError("Unsafe configuration accepted");}catch(IOException expected){}}
    public static void main(String[] args)throws Exception {
        if(TestJournals.validate(Map.of()))throw new AssertionError("File mode must remain default");
        if(!TestJournals.validate(valid()))throw new AssertionError("Explicit dedicated configuration rejected");
        for(String key:List.of("GITHUB_ACTIONS","FCP_CRASH_TEST_URL","FCP_CRASH_TEST_USER","FCP_CRASH_TEST_PASSWORD","FCP_CRASH_TEST_DRIVER_CLASSPATH")){var env=valid();env.remove(key);rejected(env);}
        for(String url:List.of("jdbc:mysql://127.0.0.1:33306/fcp_ci_main","jdbc:mysql://db.example:33306/fcp_ci_crash_0123456789abcdef","jdbc:mysql://127.0.0.1:33306/fcp_ci_crash_0123456789abcdef?user=root","jdbc:mysql://127.0.0.1:0/fcp_ci_crash_0123456789abcdef","jdbc:mysql://127.0.0.1:99999/fcp_ci_crash_0123456789abcdef")){var env=valid();env.put("FCP_CRASH_TEST_URL",url);rejected(env);}
        var env=valid();env.put("GITHUB_ACTIONS","false");rejected(env);env=valid();env.put("FCP_CRASH_TEST_PASSWORD","");rejected(env);
        System.out.println("PASS 14 journal configuration checks; no database connection attempted");
    }
}
