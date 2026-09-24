-- #1092: the cost-estimate dry-run binds the submitter's row-security values, and engines inline
-- them into plan predicate text (PostgreSQL Index Cond / Filter, MySQL attached_condition,
-- MongoDB stage filters). New estimates drop that text whenever row security applied; which
-- existing rows had it was never recorded, so strip every stored plan's per-node `detail` and
-- raw plan. Operation, target, row and cost figures are kept.

CREATE FUNCTION pg_temp.af_strip_plan_detail(node JSONB) RETURNS JSONB
    LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF node IS NULL OR jsonb_typeof(node) <> 'object' THEN
        RETURN node;
    END IF;
    RETURN node || jsonb_build_object(
        'detail', NULL::JSONB,
        'children', COALESCE(
            (SELECT jsonb_agg(pg_temp.af_strip_plan_detail(child) ORDER BY ord)
               FROM jsonb_array_elements(
                        CASE WHEN jsonb_typeof(node -> 'children') = 'array'
                             THEN node -> 'children' ELSE '[]'::JSONB END)
                    WITH ORDINALITY AS c(child, ord)),
            '[]'::JSONB));
END;
$$;

UPDATE query_estimates
   SET plan     = pg_temp.af_strip_plan_detail(plan),
       raw_plan = NULL
 WHERE plan IS NOT NULL OR raw_plan IS NOT NULL;

DROP FUNCTION pg_temp.af_strip_plan_detail(JSONB);
