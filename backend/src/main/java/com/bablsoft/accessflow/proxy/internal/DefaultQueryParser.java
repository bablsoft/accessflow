package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryEngineCatalog;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Engine-aware {@link QueryParser} that routes a submitted query to the parser for its
 * {@link DbType}: engine-managed types (non-RELATIONAL connector category) to the engine plugin
 * resolved from the {@link QueryEngineCatalog}, every relational dialect to the existing
 * JSqlParser-backed {@link SqlParserService}. The parse step is traced as
 * {@code accessflow.query.parse} (AF-454).
 */
@Service
@RequiredArgsConstructor
class DefaultQueryParser implements QueryParser {

    private final SqlParserService sqlParserService;
    private final QueryEngineCatalog engineCatalog;
    private final ObservationRegistry observationRegistry;

    @Override
    public SqlParseResult parse(String query, DbType dbType) {
        Observation observation = Observation.createNotStarted("accessflow.query.parse", observationRegistry)
                .lowCardinalityKeyValue("db_type", dbType.name())
                .start();
        try (Observation.Scope ignored = observation.openScope()) {
            SqlParseResult result = engineCatalog.isEngineManaged(dbType)
                    ? engineCatalog.engineFor(dbType).parse(query)
                    : sqlParserService.parse(query);
            observation.lowCardinalityKeyValue("outcome", "success");
            return result;
        } catch (RuntimeException ex) {
            observation.lowCardinalityKeyValue("outcome", "failure");
            observation.error(ex);
            throw ex;
        } finally {
            observation.stop();
        }
    }
}
