package dev.fcp.host;

import dev.fcp.school.SchoolFacade;
import fcp.FcpException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Projection for two trusted first-party shells. Business authorization remains server-side. */
public final class UiRoutePolicy {
    private UiRoutePolicy() {}
    public static Map<String,Object> session(SchoolFacade.Identity id) {
        requireRead(id);
        boolean admin=id.adminShell();String page=id.can("list")?"school":"ingestion";
        return Map.of("subject",id.subject(),"scope",id.scope(),
            "defaultShell",admin?"admin":"teacher",
            "allowedShells",admin?List.of("admin","teacher"):List.of("teacher"),
            "home",(admin?"/admin/fcp/":"/teacher/fcp/")+page);
    }
    /** Soybean v1.1.3 parses RuoYi-style routes itself. */
    public static List<Map<String,Object>> soybeanRoutes(SchoolFacade.Identity id) {
        requireRead(id);List<Map<String,Object>> children=new ArrayList<>();
        if(id.can("list"))children.add(Map.of("name","FcpSchool","path","school","component","fcp/school/index","hidden",false,
            "meta",Map.of("title","课堂评价","icon","mdi:clipboard-text-outline","noCache",true)));
        if(id.permissions().contains("fcp:ingest:view"))children.add(Map.of("name","FcpIngestion","path","ingestion","component","fcp/ingestion/index","hidden",false,
            "meta",Map.of("title","数据接入与业务插件","icon","mdi:database-import-outline","noCache",true)));
        return List.of(Map.of("name","Fcp","path","/fcp","component","Layout",
            "redirect",id.can("list")?"/fcp/school":"/fcp/ingestion","hidden",false,
            "meta",Map.of("title","教学工作台","icon","mdi:school-outline","noCache",true),"children",children));
    }
    private static void requireRead(SchoolFacade.Identity id) {
        if(!id.can("list")&&!id.permissions().contains("fcp:ingest:view"))throw new FcpException("FORBIDDEN","FCP workspace permission required");
    }
}
