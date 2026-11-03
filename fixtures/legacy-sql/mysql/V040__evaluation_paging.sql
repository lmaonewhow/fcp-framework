-- MySQL 8 only. Apply with a dedicated migration identity, before enabling school.full.
-- Candidate: SQL has not been run against MySQL in this build environment.
-- Online-DDL lock behavior and rollback require target-database verification.
ALTER TABLE fcp_dual_record
 ADD COLUMN class_id VARCHAR(32) COLLATE utf8mb4_bin
 GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(body_json, '$.classId'))) STORED;
CREATE INDEX idx_fcp_class_page ON fcp_dual_record(scope_id,kind,class_id,record_id);
