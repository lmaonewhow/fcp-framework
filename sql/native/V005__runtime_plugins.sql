-- Deployment identity only. No plugin receives database credentials.
-- MySQL DDL implicitly commits: provision stepwise and inspect information_schema on failure.
-- Logical schemas and separately owned physical tables use restricted additive migration APIs.
CREATE TABLE fcp_runtime_state (
 journal_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 revision BIGINT NOT NULL, state_json JSON NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE fcp_plugin_schema (
 plugin_id VARCHAR(128) NOT NULL, collection_id VARCHAR(64) NOT NULL,
 schema_version BIGINT NOT NULL, schema_json JSON NOT NULL,
 PRIMARY KEY(plugin_id,collection_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE fcp_plugin_record (
 tenant_id VARCHAR(128) NOT NULL, plugin_id VARCHAR(128) NOT NULL,
 collection_id VARCHAR(64) NOT NULL, data_scope VARCHAR(192) NOT NULL, record_id VARCHAR(64) NOT NULL,
 body JSON NOT NULL, revision BIGINT NOT NULL, del TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,plugin_id,collection_id,data_scope,record_id),
 KEY idx_plugin_active(tenant_id,plugin_id,collection_id,data_scope,del,record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE fcp_plugin_receipt (
 receipt_id CHAR(64) NOT NULL PRIMARY KEY, request_digest CHAR(64) NOT NULL,
 result_json JSON NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE fcp_plugin_storage_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id VARCHAR(128) NOT NULL, plugin_id VARCHAR(128) NOT NULL,
 principal_id VARCHAR(256) NOT NULL, principal_kind VARCHAR(16) NOT NULL,
 request_id VARCHAR(256) NOT NULL, collection_id VARCHAR(64) NOT NULL, data_scope VARCHAR(192) NOT NULL,
 record_id VARCHAR(64) NOT NULL, action VARCHAR(32) NOT NULL, revision BIGINT NOT NULL,
 occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE fcp_plugin_migration (
 step_id CHAR(64) NOT NULL PRIMARY KEY, plugin_id VARCHAR(128) NOT NULL,
 collection_id VARCHAR(64) NOT NULL, target_version BIGINT NOT NULL,
 checksum CHAR(64) NOT NULL, phase VARCHAR(32) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_migration(plugin_id,collection_id,target_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
-- Platform grants are bound to the installed immutable package digest; upgrades require review.
CREATE TABLE fcp_runtime_grant (
 tenant_id VARCHAR(128) NOT NULL, plugin_id VARCHAR(128) NOT NULL,
 package_digest CHAR(64) NOT NULL,
 capability_id VARCHAR(128) NOT NULL, operation VARCHAR(64) NOT NULL,
 permission VARCHAR(128) NOT NULL, data_scope VARCHAR(256) NOT NULL,
 revision BIGINT NOT NULL, del TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,plugin_id,capability_id,operation,data_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE fcp_runtime_task_grant (
 tenant_id VARCHAR(128) NOT NULL, task_id VARCHAR(128) NOT NULL,
 plugin_id VARCHAR(128) NOT NULL, capability_id VARCHAR(128) NOT NULL,
 operation VARCHAR(64) NOT NULL, data_scope VARCHAR(192) NOT NULL,
 package_digest CHAR(64) NOT NULL, revision BIGINT NOT NULL, del TINYINT NOT NULL DEFAULT 0, expires_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,task_id,plugin_id,capability_id,operation,data_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE fcp_runtime_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id VARCHAR(256) NOT NULL, trace_id VARCHAR(256) NOT NULL,
 tenant_id VARCHAR(128) NOT NULL, principal_id VARCHAR(256) NOT NULL,
 plugin_id VARCHAR(128) NOT NULL, action VARCHAR(256) NOT NULL,
 outcome VARCHAR(256) NOT NULL, occurred_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE fcp_plugin_physical_schema (
 table_name VARCHAR(64) NOT NULL PRIMARY KEY, plugin_id VARCHAR(128) NOT NULL,
 structure_id VARCHAR(64) NOT NULL, schema_json JSON NULL, schema_version BIGINT NOT NULL DEFAULT 0, ddl_revision BIGINT NOT NULL DEFAULT 0, UNIQUE KEY uk_physical_owner(plugin_id,structure_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE fcp_plugin_ddl_step (
 step_id CHAR(64) NOT NULL PRIMARY KEY, table_name VARCHAR(64) NOT NULL,
 target_version BIGINT NOT NULL, checksum CHAR(64) NOT NULL, phase VARCHAR(32) NOT NULL,
 before_digest CHAR(64) NOT NULL, after_digest CHAR(64) NULL,
 principal_id VARCHAR(256) NOT NULL, request_id VARCHAR(256) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_ddl_step(table_name,target_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE fcp_plugin_storage_release (
 plugin_id VARCHAR(128) NOT NULL PRIMARY KEY, schema_version BIGINT NOT NULL,
 declaration_json JSON NOT NULL, declaration_digest CHAR(64) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
