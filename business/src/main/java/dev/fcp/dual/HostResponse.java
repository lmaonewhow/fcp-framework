package dev.fcp.dual;

import fcp.Json;

/** Preserve FCP JSON numeric types without inheriting host-specific Jackson serializers.
 * The Spring adapter emits this body with application/json and the host's familiar R envelope.
 * Authentication and authorization still execute in the actual host.
 */
public final class HostResponse {
    private HostResponse(){}
    public static String ok(Object data){return Json.write(Json.obj("code",200,"msg","操作成功","data",data));}
}
