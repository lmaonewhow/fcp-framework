-- PROTOCOL EXAMPLE ONLY. Named placeholders require a trusted application binder.
-- This file is NOT an executable migration or a stored-procedure implementation.
-- All participants must take locks in this order. Missing row => deny/rollback.
-- No HTTP, AI inference, object storage or external device call inside this transaction.
SET SESSION time_zone = '+00:00';
START TRANSACTION;
SELECT epoch, enabled FROM ed_core_write_fence
 WHERE tenant_id=:tenant AND fence_key=:capability FOR SHARE;
SELECT epoch, enabled FROM ed_core_access_epoch
 WHERE tenant_id=:tenant AND subject_key=:actor FOR SHARE;
SELECT epoch, enabled FROM ed_core_execution_grant
 WHERE tenant_id=:tenant AND scope_key='tenant' AND capability_key=:capability AND manifest_digest=:executing_manifest FOR SHARE;
-- For a school-scoped operation, also lock the exact school grant; no fallback.
-- :scope comes from verified domain ownership (school:<canonical ID>), not a client header.
SELECT epoch, enabled FROM ed_core_execution_grant
 WHERE tenant_id=:tenant AND scope_key=:scope AND capability_key=:capability AND manifest_digest=:executing_manifest FOR SHARE;
-- Tenant-only operations lock the tenant grant once; skip the repeated same-row lookup.
-- Application verifies all current epochs, current authorization and requested scope.
-- For async results also lock the task and verify current claim_token, lease, state,
-- tenant, operation, required capability and allowed executor manifest before applying effects.
-- Domain aggregate locks follow (account, judgement series, or other domain root).
SELECT current_judgement_id, row_version FROM ed_eval_judgement_series
 WHERE tenant_id=:tenant AND id=:series FOR UPDATE;
-- Stable intent uniqueness is independent of transport and code versions.
-- Reserve/check (tenant_id,effect_namespace,intent_id) after domain lock;
-- duplicate requests reauthorize and compare normalized fingerprint, not raw JSON order.
-- Insert the revision, CAS the series head, persist successful audit and outbox
-- in THIS transaction. Abort on affected-row mismatch or any missing audit/outbox.
COMMIT;

-- Disable/revoke transaction, run independently with proper admin authorization:
START TRANSACTION;
SELECT epoch, enabled FROM ed_core_execution_grant
 WHERE tenant_id=:tenant AND scope_key=:revoked_scope AND capability_key=:capability AND manifest_digest=:revoked_manifest FOR UPDATE;
UPDATE ed_core_execution_grant SET epoch=epoch+1, enabled=0, row_version=row_version+1
 WHERE tenant_id=:tenant AND scope_key=:revoked_scope AND capability_key=:capability AND manifest_digest=:revoked_manifest;
-- Append authorized control audit in the same transaction. Only after COMMIT can
-- controller acknowledge that this executor's future commits are fenced out.
COMMIT;
-- Existing transactions holding shared authority locks finish before this revoke
-- commits. Tasks that arrive afterward see the new epoch and cannot commit.
-- External side effects already executed still require provider idempotency/reconciliation.
