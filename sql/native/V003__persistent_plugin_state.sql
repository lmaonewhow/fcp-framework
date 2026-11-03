-- Append-only migration for the FCP 0.4.2 -> 0.5.0 schema.
-- Apply with deployment credentials; preserve V001/V002 byte-for-byte.
CREATE TABLE fcp_plugin_state (
  scope_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  plugin_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  revision BIGINT NOT NULL,
  updated_by VARCHAR(100) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(scope_id,plugin_id),
  CONSTRAINT chk_fcp_state_revision CHECK(revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE fcp_plugin_state_audit (
  scope_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  plugin_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  revision BIGINT NOT NULL,
  enabled BOOLEAN NOT NULL,
  actor VARCHAR(100) NOT NULL,
  changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(scope_id,plugin_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
