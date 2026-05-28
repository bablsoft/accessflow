-- AF-353: per-datasource reviewer assignment. Each row attaches either a user
-- or a group to a datasource as an eligible reviewer. When a datasource has
-- at least one row in this table, ONLY listed reviewers (and members of listed
-- groups) can see/decide its queries — the eligibility check upgrades from
-- review-plan-only to plan AND datasource-scope. Datasources with zero rows
-- behave exactly as before (plan approvers alone).

CREATE TABLE datasource_reviewers (
    id            UUID        PRIMARY KEY,
    datasource_id UUID        NOT NULL REFERENCES datasources(id) ON DELETE CASCADE,
    user_id       UUID                 REFERENCES users(id) ON DELETE CASCADE,
    group_id      UUID                 REFERENCES user_groups(id) ON DELETE CASCADE,
    created_by    UUID        NOT NULL REFERENCES users(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_datasource_reviewers_user_xor_group
        CHECK ((user_id IS NOT NULL) <> (group_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_datasource_reviewers_user
    ON datasource_reviewers (datasource_id, user_id)
    WHERE user_id IS NOT NULL;

CREATE UNIQUE INDEX uq_datasource_reviewers_group
    ON datasource_reviewers (datasource_id, group_id)
    WHERE group_id IS NOT NULL;

CREATE INDEX idx_datasource_reviewers_datasource ON datasource_reviewers (datasource_id);
CREATE INDEX idx_datasource_reviewers_user       ON datasource_reviewers (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_datasource_reviewers_group      ON datasource_reviewers (group_id) WHERE group_id IS NOT NULL;
