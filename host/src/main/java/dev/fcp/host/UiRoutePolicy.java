package dev.fcp.host;

import dev.fcp.school.SchoolFacade;
import fcp.FcpException;
import java.util.List;
import java.util.Map;

/** Projection for two trusted first-party shells. All business authorization stays server-side. */
public final class UiRoutePolicy {
    private UiRoutePolicy() {}
    public static Map<String,Object> session(SchoolFacade.Identity id) {
        requireRead(id);
        boolean admin = id.adminShell();
        return Map.of("subject", id.subject(), "scope", id.scope(),
            "defaultShell", admin ? "admin" : "teacher",
            "allowedShells", admin ? List.of("admin", "teacher") : List.of("teacher"),
            "home", admin ? "/admin/fcp/school" : "/teacher/fcp/school");
    }
    /** Soybean v1.1.3 parses RuoYi-style routes itself: do NOT preconvert to Elegant routes. */
    public static List<Map<String,Object>> soybeanRoutes(SchoolFacade.Identity id) {
        requireRead(id);
        return List.of(Map.of("name", "Fcp", "path", "/fcp", "component", "Layout",
            "redirect", "/fcp/school", "hidden", false,
            "meta", Map.of("title", "教学工作台", "icon", "mdi:school-outline", "noCache", true),
            "children", List.of(Map.of("name", "FcpSchool", "path", "school",
                "component", "fcp/school/index", "hidden", false,
                "meta", Map.of("title", "课堂评价", "icon", "mdi:clipboard-text-outline", "noCache", true)))));
    }
    private static void requireRead(SchoolFacade.Identity id) {
        if (!id.can("list")) throw new FcpException("FORBIDDEN", "FCP workspace permission required");
    }
}
