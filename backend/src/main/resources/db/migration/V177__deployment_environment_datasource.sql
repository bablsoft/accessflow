-- #877 (epic #870): deployment environments become usable schema-promotion targets. An environment
-- may now name the datasource its schema changes land on (`datasource_id`, nullable — NULL is a
-- deploy-only environment, so every existing deploygov behaviour is unchanged), and `sort_order`
-- becomes a real promotion ladder: V149 declared it `NOT NULL DEFAULT 0` with no uniqueness, so every
-- environment sat at 0 unless an admin typed a number, and a "lower-ordered environments must be
-- applied" gate would evaluate the empty set and fail OPEN. The backfill renumbers each pipeline's
-- environments to distinct, contiguous values from 0 in their current (sort_order, name) order —
-- a hand-typed 10/20/30 becomes 0/1/2 — and the unique constraint keeps them distinct from here on.
--
-- `datasource_id` is a bare UUID with no foreign key, per the module convention stated in V149: the
-- environment row survives deletion of the datasource it names, exactly like review_plan_id.

ALTER TABLE deployment_environments ADD COLUMN datasource_id UUID;

CREATE INDEX idx_deployment_environments_datasource_id ON deployment_environments (datasource_id);

UPDATE deployment_environments e
   SET sort_order = r.rn
  FROM (SELECT id,
               row_number() OVER (PARTITION BY pipeline_id ORDER BY sort_order, name) - 1 AS rn
          FROM deployment_environments) r
 WHERE e.id = r.id;

ALTER TABLE deployment_environments
    ADD CONSTRAINT uq_deployment_environments_pipeline_sort_order UNIQUE (pipeline_id, sort_order);
