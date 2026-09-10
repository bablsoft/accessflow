package com.bablsoft.accessflow;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Clears the shared {@code vector_store} table between tests — with {@code TRUNCATE}, never
 * {@code DELETE}.
 *
 * <p>The distinction is not cosmetic, and getting it wrong fails silently and intermittently in
 * some <em>other</em> test class. {@code vector_store} carries an HNSW index
 * ({@code vector_store_embedding_idx}, V69), and HNSW is an <strong>approximate</strong> index: a
 * scan walks a fixed budget of graph neighbours ({@code hnsw.ef_search}, 40 by default) and returns
 * whatever live rows it reached — it does not error, and it does not fall back to an exact scan when
 * it reaches none.
 *
 * <p>{@code DELETE} leaves the deleted rows as dead tuples in the heap and as dead entries in that
 * graph. A class that ingests the ~510-chunk help corpus and then deletes it leaves the table
 * <em>empty</em> but ~150 pages wide, so {@link DatabaseResetTestExecutionListener}'s
 * "truncate only the tables that actually hold rows" pre-pass skips it and the bloat survives the
 * class. Let autoanalyze fire in that window and {@code pg_class.reltuples} is stamped 0 against a
 * 150-page relation — after which the planner prices the HNSW index scan below a sequential scan for
 * <em>any</em> later query, however few rows the table now really holds. The next class to seed a
 * couple of rows and immediately run a filtered similarity search gets an index scan whose budget is
 * spent entirely on the dead graph, and reads back nothing:
 *
 * <pre>
 * A) after DELETE:    reltuples 0 / relpages 150  -&gt;  0 hits  (row present, distance 0.0)
 * B) after TRUNCATE:  reltuples 0 / relpages 0    -&gt;  1 hit
 * </pre>
 *
 * <p>{@code TRUNCATE} reclaims the heap and rebuilds the index empty, so the relation is genuinely
 * 0 pages and the planner has nothing to mis-price. Every test that clears this table must use it,
 * including the classes that only clear up after themselves — the damage lands on whoever runs next,
 * not on the class that caused it.
 */
public final class VectorStoreTestTable {

    private VectorStoreTestTable() {
    }

    /** Empties {@code vector_store} and reclaims its heap and HNSW index. */
    public static void clear(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("TRUNCATE TABLE vector_store");
    }
}
