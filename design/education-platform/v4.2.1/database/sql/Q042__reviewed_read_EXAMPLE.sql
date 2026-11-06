-- EXAMPLE ONLY: parameters and scope are supplied by a trusted server query compiler.
-- School admins cannot supply tenant, purpose, authorization predicates, raw ORDER BY or table names.
-- A single authorized class is shown; query_compiler.py handles a bounded class set.
SELECT id, person_id, class_id, occurred_at, event_type, redacted_summary
FROM ed_reporting_timeline
WHERE tenant_id=:trusted_tenant
  AND class_id=:authorized_class_id
  AND purpose_key='teacher.timeline'
  AND projection_kind='timeline-v1'
  AND is_visible=1
  AND id>:last_seen_id
ORDER BY id ASC
LIMIT :bounded_limit;
-- Actual code must choose an ordering consistent with its cursor. Do not combine
-- an id cursor with occurred_at-only ordering. No result caching across scope epochs.
-- Parent/public purposes require separate reviewed projection, field whitelist and authorization.
