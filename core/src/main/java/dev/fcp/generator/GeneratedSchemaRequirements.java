package dev.fcp.generator;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Necessary schema prerequisites of the pinned native CRUD entity/mapper templates. */
public final class GeneratedSchemaRequirements {
    private static final Set<String> AUDIT_COLUMNS = Set.of(
            "create_dept", "create_by", "create_time", "update_by", "update_time");

    private GeneratedSchemaRequirements() { }

    public static void requirePrimary(String selected, String primary) {
        // Native generated mappers have no @DS and run on the host's primary datasource.
        if (primary == null || primary.isBlank() || !primary.equals(selected)) {
            throw new IllegalArgumentException("当前直接应用仅支持宿主主数据源；其他数据源请下载后审查并配置路由");
        }
    }

    public static void requireAuditColumns(Collection<String> actualColumns) {
        Set<String> names = new TreeSet<>();
        if (actualColumns != null) {
            for (String name : actualColumns) {
                if (name != null) names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        Set<String> missing = new TreeSet<>(AUDIT_COLUMNS);
        missing.removeAll(names);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("当前 CRUD 模板要求审计字段，实际业务表缺少："
                    + String.join(", ", missing) + "；请先审查表结构，或下载后调整实体映射");
        }
    }
}
