package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuerySnapshotViewTest {

    private QuerySnapshotView view(List<String> referencedTables) {
        return new QuerySnapshotView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "SELECT 1", QueryType.SELECT, false,
                DbType.POSTGRESQL, referencedTables, "hash", null, "[]", 1L, 10,
                Instant.now(), Instant.now());
    }

    @Test
    void nullReferencedTablesBecomesEmptyImmutableList() {
        var view = view(null);

        assertThat(view.referencedTables()).isEmpty();
        assertThatThrownBy(() -> view.referencedTables().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void referencedTablesAreDefensivelyCopied() {
        var mutable = new ArrayList<>(List.of("public.users"));
        var view = view(mutable);
        mutable.add("public.orders");

        assertThat(view.referencedTables()).containsExactly("public.users");
    }
}
