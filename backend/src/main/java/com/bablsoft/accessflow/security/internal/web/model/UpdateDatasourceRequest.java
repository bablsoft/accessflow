package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.SslMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UpdateDatasourceRequest(
        @Size(min = 1, max = 255, message = "{validation.datasource_name.required}") String name,
        @Size(min = 1, max = 255, message = "{validation.host.required}") String host,
        @Min(value = 1, message = "{validation.port.range}")
        @Max(value = 65535, message = "{validation.port.range}") Integer port,
        @Size(min = 1, max = 255, message = "{validation.database_name.required}") String databaseName,
        @Size(min = 1, max = 255, message = "{validation.username.required}") String username,
        @Size(min = 1, max = 4096) String password,
        SslMode sslMode,
        @Min(value = 1, message = "{validation.pool_size.range}")
        @Max(value = 200, message = "{validation.pool_size.range}") Integer connectionPoolSize,
        @Min(value = 1, message = "{validation.max_rows.range}")
        @Max(value = 1_000_000, message = "{validation.max_rows.range}") Integer maxRowsPerQuery,
        Boolean requireReviewReads,
        Boolean requireReviewWrites,
        UUID reviewPlanId,
        Boolean aiAnalysisEnabled,
        UUID aiConfigId,
        Boolean textToSqlEnabled,
        Boolean clearAiConfig,
        @Size(max = 2048, message = "{validation.jdbc_url.length}")
        @Pattern(regexp = "^jdbc:[a-zA-Z][a-zA-Z0-9+\\-.]*:.+$",
                message = "{validation.jdbc_url.format}") String jdbcUrlOverride,
        // Full-list replacement merged by endpoint id: null keeps the current endpoints, an empty
        // list deletes them all (AF-457).
        @Size(max = 5, message = "{validation.read_replicas.max}")
        List<@Valid ReadReplicaRequest> readReplicas,
        Boolean active,
        @Size(max = 255, message = "{validation.local_datacenter.max}") String localDatacenter,
        // API key for the search engines (Elasticsearch / OpenSearch); a blank value clears it.
        @Size(max = 4096, message = "{validation.api_key.max}") String apiKey,
        Boolean resultCacheEnabled,
        @Min(value = 1, message = "{validation.result_cache_ttl.range}")
        @Max(value = 86_400, message = "{validation.result_cache_ttl.range}")
        Integer resultCacheTtlSeconds
) {}
