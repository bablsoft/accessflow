-- AF-898: which governance domains an organization intends to use. Database access governance is
-- always on and has no flag; these two toggle the newer domains. They are an onboarding hint, not
-- an entitlement — they decide which first-run checklist steps appear and never gate routes,
-- permissions or navigation, so apigov/deploygov stay fully usable whatever was picked.

ALTER TABLE organizations
    ADD COLUMN governs_apis BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN governs_deployments BOOLEAN NOT NULL DEFAULT false;

-- Backfill: an org already using a domain must never have its checklist re-opened with a step it
-- has demonstrably satisfied.
UPDATE organizations o
   SET governs_apis = EXISTS (SELECT 1 FROM api_connectors c WHERE c.organization_id = o.id),
       governs_deployments = EXISTS (SELECT 1 FROM deployment_pipelines p WHERE p.organization_id = o.id);
