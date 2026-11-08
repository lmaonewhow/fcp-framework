package org.dromara.fcp.generator;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import dev.fcp.generator.GeneratedSourceWorkspace;
import jakarta.servlet.http.HttpServletRequest;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.generator.service.IGenTableService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;

/** Explicitly enabled development source operation. Never executes generated SQL or deploys a JAR. */
@RestController
@ConditionalOnProperty(name = "fcp.generator.enabled", havingValue = "true")
@RequestMapping("/fcp/generator")
@SaCheckPermission("tool:gen:code")
public class FcpGeneratorController {
    private final IGenTableService generator;
    private final GeneratedSourceWorkspace workspace;
    private final DataSource source;
    private final Path root;

    public FcpGeneratorController(IGenTableService generator, DataSource source,
            @Value("${fcp.generator.workspace:}") String directory) throws IOException {
        if (directory.isBlank() || !Path.of(directory).isAbsolute()) {
            throw new IOException("Generator requires an explicit absolute FCP development workspace");
        }
        root = Path.of(directory).normalize();
        for (String marker : List.of("upstreams.lock.json", "tools/native.py", "AGENTS.md")) {
            Path path = root.resolve(marker);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                throw new IOException("Generator requires an FCP source workspace, not a release directory");
            }
        }
        this.generator = generator;
        this.source = source;
        this.workspace = new GeneratedSourceWorkspace(root);
    }

    /** Cookies alone must not authorize writes to the configured development sources. */
    @ModelAttribute
    public void authorize(HttpServletRequest request) throws SQLException {
        StpUtil.checkLogin();
        String header = request.getHeader("Authorization");
        String presented = header != null && header.startsWith("Bearer ") ? header.substring(7) : header;
        String token = StpUtil.getTokenValue();
        if (presented == null || token == null || !MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("需要显式的原生登录授权");
        }
        // Source changes affect every tenant: a tenant admin or ordinary generator role is insufficient.
        if (!"000000".equals(LoginHelper.getTenantId()) || !LoginHelper.isSuperAdmin()) {
            throw new IllegalArgumentException("仅平台开发管理员可应用源码");
        }
        // Revalidate account/tenant activation at every request, including apply and rollback.
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "SELECT u.user_id FROM sys_user u JOIN sys_tenant t ON t.tenant_id=u.tenant_id "
                + "WHERE u.user_id=1 AND u.tenant_id='000000' AND u.status='0' AND u.del_flag='0' "
                + "AND t.status='0' AND t.del_flag='0' AND (t.expire_time IS NULL OR t.expire_time>CURRENT_TIMESTAMP)")) {
            query.setQueryTimeout(5);
            try (var result = query.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("开发管理员或租户已停用");
            }
        }
    }

    private String owner() { return LoginHelper.getTenantId() + ":" + LoginHelper.getUserIdStr(); }
    private ResponseEntity<R<Map<String, Object>>> ok(Map<String, Object> result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(result));
    }

    @GetMapping("/config")
    public ResponseEntity<R<Map<String, Object>>> config() {
        return ok(Map.of("enabled", true, "workspace", "FCP 开发源码工作区"));
    }

    public record PlanRequest(List<String> tableIds) { }

    @PostMapping("/plans")
    @Log(title = "代码应用预览", businessType = BusinessType.GENCODE, isSaveRequestData = false, isSaveResponseData = false)
    public ResponseEntity<R<Map<String, Object>>> plan(@RequestBody PlanRequest request) throws IOException {
        List<String> ids = request.tableIds();
        if (ids == null || ids.isEmpty() || ids.size() > 10 || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("请选择 1 至 10 张不同的表");
        }
        Map<String, byte[]> tableZips = new LinkedHashMap<>();
        for (String id : ids) {
            if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("表编号无效");
            var table = generator.selectGenTableById(Long.parseLong(id));
            if (table == null || table.getColumns() == null || table.getColumns().isEmpty() || table.getColumns().size() > 200) {
                throw new IllegalArgumentException("表不存在或字段数量不在 1 至 200 范围内");
            }
            if (!table.isCrud()) {
                throw new IllegalArgumentException("当前直接应用仅支持普通 CRUD 模板；树形或自定义模板请下载后审查");
            }
            if (table.getColumns().stream().anyMatch(column -> "checkbox".equals(column.getHtmlType())
                    && !"String".equals(column.getJavaType()))) {
                throw new IllegalArgumentException("直接应用的复选框字段必须使用 String 类型保存逗号分隔值");
            }
            tableZips.put(id, generator.downloadCode(Long.parseLong(id)));
        }
        return ok(workspace.plan(owner(), tableZips));
    }

    @PostMapping("/plans/{id}/apply")
    @Log(title = "应用生成源码", businessType = BusinessType.GENCODE, isSaveRequestData = false, isSaveResponseData = false)
    public ResponseEntity<R<Map<String, Object>>> apply(@PathVariable String id) throws IOException {
        return ok(workspace.apply(owner(), id));
    }

    @GetMapping("/operations")
    public ResponseEntity<R<Map<String, Object>>> history() throws IOException { return ok(workspace.history(owner())); }

    @PostMapping("/operations/{id}/rollback")
    @Log(title = "撤销生成源码", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    public ResponseEntity<R<Map<String, Object>>> rollback(@PathVariable String id) throws IOException {
        return ok(workspace.rollback(owner(), id));
    }

    @ExceptionHandler({IOException.class, IllegalArgumentException.class})
    public ResponseEntity<R<Void>> rejected(Exception failure) {
        String message = Objects.toString(failure.getMessage(), "无法完成操作，请检查开发工作区");
        message = message.replace(root.toString(), "[开发工作区]");
        return ResponseEntity.status(failure instanceof IOException ? 409 : 403)
                .cacheControl(CacheControl.noStore()).body(R.fail(message.length() > 500 ? message.substring(0, 500) : message));
    }
}
