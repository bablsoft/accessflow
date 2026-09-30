-- #1129: the human an agent broke glass for (X-AccessFlow-On-Behalf-Of, #874) is a second submitter
-- identity and may never acknowledge the retro-review. Denormalised onto the event so the workflow
-- module can guard API and deployment targets without depending on apigov / deploygov.
-- Bare UUID, no FK — the table's convention, so deleting a user never erases the forensic record.
ALTER TABLE break_glass_events ADD COLUMN on_behalf_of_user_id UUID;

UPDATE break_glass_events e
SET on_behalf_of_user_id = q.on_behalf_of_user_id
FROM query_requests q
WHERE e.query_request_id = q.id
  AND q.on_behalf_of_user_id IS NOT NULL;

UPDATE break_glass_events e
SET on_behalf_of_user_id = a.on_behalf_of_user_id
FROM api_requests a
WHERE e.api_request_id = a.id
  AND a.on_behalf_of_user_id IS NOT NULL;

UPDATE break_glass_events e
SET on_behalf_of_user_id = d.on_behalf_of_user_id
FROM deployment_requests d
WHERE e.deployment_request_id = d.id
  AND d.on_behalf_of_user_id IS NOT NULL;
