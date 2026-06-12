package com.bablsoft.accessflow.engine.elasticsearch;

import tools.jackson.databind.JsonNode;

/**
 * One document of a {@code bulk} index request: an optional {@code _id} and the source document.
 * AccessFlow's bulk envelope only carries index (create) actions — update / delete bulk actions are
 * rejected at parse time so the operation classifies cleanly as a single {@code INSERT} and the
 * approval stays meaningful; users mutate with {@code update_by_query} / {@code delete_by_query}.
 */
record EsBulkItem(String id, JsonNode document) {
}
