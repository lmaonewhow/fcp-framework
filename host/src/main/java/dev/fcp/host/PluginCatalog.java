package dev.fcp.host;

import fcp.Json;
import fcp.host.*;
import fcp.plugin.PluginManifest;
import fcp.runtime.EngineRuntimePort;
import java.util.*;

/** Server projection of the enabled snapshot. Never exposes package paths or host credentials. */
public final class PluginCatalog {
    private PluginCatalog() { }
    public static Map<String,Object> project(HostCapabilities host, TrustedContext context,
            Map<String,PluginManifest> enabled, long generation, Set<String> hostPermissions) {
        Set<String> permissions=new TreeSet<>();
        for(String permission:hostPermissions)if(permission.startsWith("fcp:plugin:"))permissions.add(permission.substring("fcp:plugin:".length()));
        List<Object> pages=new ArrayList<>(),slots=new ArrayList<>();
        for(var entry:new TreeMap<>(enabled).entrySet()) {
            String plugin=entry.getKey();PluginManifest manifest=entry.getValue();
            Map<String,String> exports=new HashMap<>();
            for(var capability:manifest.provides()) {
                HostCapabilities.Decision decision=host.authorize(context,new HostCapabilities.Grant(plugin,capability.id(),"invoke","tenant:"+context.tenantId()));
                if(decision!=null&&decision.allowed())exports.put(capability.id(),EngineRuntimePort.export(plugin,capability.id()));
            }
            for(String kind:List.of("pages","slots")) {
                Object raw=manifest.ui().getOrDefault(kind,List.of());
                for(Object value:(List<?>)raw) {
                    @SuppressWarnings("unchecked") Map<String,Object> item=(Map<String,Object>)value;
                    List<?> requiredPermissions=(List<?>)item.get("permissions"),requiredCapabilities=(List<?>)item.get("capabilities");
                    if(!permissions.containsAll(requiredPermissions)||!exports.keySet().containsAll(requiredCapabilities))continue;
                    // Host slots are an explicit allowlist, never an arbitrary DOM selector.
                    if(kind.equals("slots")&&!host.descriptor().capabilities().containsKey("ui.slot."+item.get("slotId")))throw new fcp.FcpException("UI_SLOT_UNAVAILABLE","Required host slot unavailable");
                    @SuppressWarnings("unchecked") Map<String,Object> projected=(Map<String,Object>)Json.parse(Json.write(item));
                    String id=(String)item.get("id");projected.put("id",plugin+":"+id);projected.put("pluginId",plugin);
                    if(kind.equals("pages"))projected.put("path","/fcp/plugins/"+plugin+"/"+id);
                    projected.put("capabilities",requiredCapabilities.stream().map(c->exports.get((String)c)).toList());
                    replaceActions(projected.get("content"),exports);
                    (kind.equals("pages")?pages:slots).add(projected);
                }
            }
        }
        return Json.obj("catalog",Json.obj("contractVersion","1.0","schemaVersion",1,"revision",generation,"pages",pages,"slots",slots),
            "context",Json.obj("userId",context.principal().id(),"tenantId",context.tenantId(),"language","zh-CN","permissions",new ArrayList<>(permissions)));
    }
    @SuppressWarnings("unchecked") private static void replaceActions(Object value,Map<String,String> exports) {
        Map<String,Object> node=(Map<String,Object>)value;
        if(node.containsKey("capabilityId"))node.put("capabilityId",exports.get(node.get("capabilityId")));
        for(Object child:(List<?>)node.getOrDefault("children",List.of()))replaceActions(child,exports);
    }
}
