package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.proxy.api.DatasourceHealthService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.DatasourceHealthPageResponse;
import com.bablsoft.accessflow.security.internal.web.model.DatasourceHealthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/datasource-health")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Datasource Health",
        description = "Per-datasource operational health: pool gauges, 24h query volume, "
                + "latency percentiles, error count (ADMIN only)")
@RequiredArgsConstructor
class AdminDatasourceHealthController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DatasourceHealthService datasourceHealthService;

    @GetMapping
    @Operation(summary = "List per-datasource health snapshots for the caller's organization "
            + "(paginated, default 50). Snapshots are cached ~30s.")
    @ApiResponse(responseCode = "200", description = "Page of datasource health snapshots")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    DatasourceHealthPageResponse list(Authentication authentication,
                                      @PageableDefault(size = 50) Pageable pageable) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var pageRequest = SpringPageableAdapter.toPageRequest(pageable);
        if (pageRequest.size() > MAX_PAGE_SIZE) {
            pageRequest = new PageRequest(pageRequest.page(), MAX_PAGE_SIZE, pageRequest.sort());
        }
        var page = datasourceHealthService.snapshot(caller.organizationId(), pageRequest)
                .map(DatasourceHealthResponse::from);
        return DatasourceHealthPageResponse.from(page);
    }
}
