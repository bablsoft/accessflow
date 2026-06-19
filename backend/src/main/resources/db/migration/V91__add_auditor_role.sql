-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V91__add_auditor_role.sql.conf sets executeInTransaction=false so Flyway runs this
-- statement autocommit. AUDITOR is a dedicated read-only role for the compliance-reporting views
-- (auditor dashboard + signed PII/PCI/GDPR access and DDL/DELETE regulatory-trail exports) — see
-- issue #459. It maps to the Spring Security authority ROLE_AUDITOR with no schema change beyond
-- this enum value.
ALTER TYPE user_role_type ADD VALUE IF NOT EXISTS 'AUDITOR';
