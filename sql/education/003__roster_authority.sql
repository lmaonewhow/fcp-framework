-- Independent reviewed extension, not a change to the locked P042 design model.
-- FRESH OWNED ISOLATED TEST DATABASE ONLY. No runtime DDL, migration registration or grants.
CREATE TABLE ed_host_roster_role (
  tenant_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  native_role_id BIGINT NOT NULL,
  admin_kind VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
  school_id BIGINT UNSIGNED NOT NULL,
  operation_key VARCHAR(40) COLLATE utf8mb4_0900_bin NOT NULL,
  purpose_key VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
  enabled TINYINT UNSIGNED NOT NULL DEFAULT 0,
  source_approval_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  created_at DATETIME(6) NOT NULL,
  row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY(tenant_id,native_role_id,school_id,operation_key,purpose_key),
  CONSTRAINT ck_ed_roster_role_kind CHECK ((admin_kind='TENANT_ADMIN' AND school_id=0) OR (admin_kind='SCHOOL_ADMIN' AND school_id>0)),
  CONSTRAINT ck_ed_roster_role_operation CHECK (operation_key IN ('student:read','teacher:read')),
  CONSTRAINT ck_ed_roster_role_purpose CHECK (purpose_key='school-roster-metadata-v1'),
  CONSTRAINT ck_ed_roster_role_enabled CHECK (enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
-- Only a separately approved deployer can provision explicit native role, scope and purpose rows.
-- This metadata purpose never authorizes person identity display or writing shared natural persons.
