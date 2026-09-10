package com.bablsoft.accessflow.workflow.api;

/** How far a grant's table coverage reaches (issue AF-859). */
public enum TableScope {

    /**
     * The grant imposes no allow-list, so it covers every table on the datasource — including ones
     * that do not exist yet. Deliberately never expanded into a list: enumerating would need a live
     * schema read, which this feature never performs, and would go stale the moment a table is
     * created.
     */
    ALL_TABLES,

    /** The grant carries an allow-list, and an entry in it covers the table that was asked about. */
    ALLOW_LISTED
}
