-- Deployment credentials only. Runtime grants do not inherit across package digests.
-- Hash primary key avoids oversized MySQL composite keys; readers verify every full identity column.
CREATE TABLE fcp_plugin_version_grant (
 grant_id CHAR(64) NOT NULL PRIMARY KEY,
 tenant_id VARCHAR(128) NOT NULL, plugin_id VARCHAR(128) NOT NULL,
 package_version VARCHAR(128) NOT NULL, package_digest CHAR(64) NOT NULL,
 capability_id VARCHAR(128) NOT NULL, operation VARCHAR(64) NOT NULL,
 data_scope VARCHAR(256) NOT NULL,
 principal_kind VARCHAR(16) NOT NULL, principal_id VARCHAR(256) NOT NULL,
 permission VARCHAR(128) NOT NULL, revision BIGINT NOT NULL,
 del TINYINT NOT NULL DEFAULT 0, expires_at DATETIME(6) NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 KEY idx_version_grant(tenant_id,plugin_id,package_digest,del)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
-- Revocation sets del=1. No business or approval records are hard deleted.
