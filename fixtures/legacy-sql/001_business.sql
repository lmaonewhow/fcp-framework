-- MySQL 8; apply to an isolated test schema first. No changes to existing business tables.
CREATE TABLE IF NOT EXISTS fcp_dual_record (
  scope_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  kind VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  record_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  version BIGINT NOT NULL,
  body_json JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(scope_id,kind,record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS fcp_dual_request (
  scope_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  subject_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  request_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  result_json JSON NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(scope_id,subject_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS fcp_dual_audit (
  audit_id BIGINT NOT NULL AUTO_INCREMENT,
  scope_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  kind VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  record_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  event_json JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(audit_id), KEY idx_record(scope_id,kind,record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
