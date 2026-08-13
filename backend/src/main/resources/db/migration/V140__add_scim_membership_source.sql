-- #621: SCIM-pushed group memberships get their own provenance so neither SSO-login IDP sync
-- (which replaces all IDP rows) nor admin MANUAL edits ever touch them.
ALTER TYPE user_group_membership_source ADD VALUE IF NOT EXISTS 'SCIM';
