import dev.fcp.host.UiRoutePolicy;
import dev.fcp.school.SchoolFacade;
import java.util.*;
/** JVM component test only; no Spring/HTTP/browser claim. */
public class UiRoutePolicyTest {
  public static void main(String[] args) {
    int checks=0;
    for(String path: List.of("fcp/plugins/exam/home","fcp/plugins/roster.business_v1/overview-page")) { if(!UiRoutePolicy.isPluginPagePath(path))throw new AssertionError(path);checks++; }
    for(String path: List.of("fcp/plugins/../home","fcp/plugins/exam/home.js/extra","assets/missing.js","fcp/ui/catalog","/fcp/plugins/exam/home","fcp/plugins/exam/%2e","fcp/plugins/exam/a..b","fcp/plugins/"+"x".repeat(129)+"/home","fcp/plugins/"+"a.".repeat(50000)+"/home")) { if(UiRoutePolicy.isPluginPagePath(path))throw new AssertionError(path);checks++; }
    var pluginOnly=new SchoolFacade.Identity("tenant-test","test",Set.of("fcp:plugin:view"),Set.of());
    if(!UiRoutePolicy.session(pluginOnly).get("home").equals("/teacher/fcp/plugins"))throw new AssertionError("plugin home");checks++;
    var children=(List<?>)UiRoutePolicy.soybeanRoutes(pluginOnly).get(0).get("children");
    if(children.size()!=1 || !((Map<?,?>)children.get(0)).get("component").equals("fcp/plugins/index"))throw new AssertionError("plugin route");checks++;
    var legacy=new SchoolFacade.Identity("tenant-test","test",Set.of("fcp:eval:list"),Set.of());
    var legacyChildren=(List<?>)UiRoutePolicy.soybeanRoutes(legacy).get(0).get("children");
    if(legacyChildren.stream().anyMatch(x -> ((Map<?,?>)x).get("component").equals("fcp/plugins/index")))throw new AssertionError("legacy grant expanded");checks++;
    System.out.println("PASS "+checks+" route policy component assertions; Spring/browser NOT_RUN");
  }
}
