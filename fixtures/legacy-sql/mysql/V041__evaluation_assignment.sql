-- Candidate MySQL 8 migration. Execute through the host's deployment migration identity.
-- Not run by a plugin or an HTTP installation request. Not executed in this delivery environment.
CREATE TABLE fcp_eval_assignment (
  scope_id VARCHAR(100) NOT NULL,
  subject_id VARCHAR(100) NOT NULL,
  class_id VARCHAR(32) NOT NULL,
  PRIMARY KEY (scope_id, subject_id, class_id)
) ENGINE=InnoDB;
-- Store the exact ScopeNames.tenant(...) value, e.g. tenant-000000, not a client-supplied tenant.
-- Grant rows are host-administered. Normal plugin credentials must not write this table.
