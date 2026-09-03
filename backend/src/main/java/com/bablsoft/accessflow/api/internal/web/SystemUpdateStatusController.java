package com.bablsoft.accessflow.api.internal.web;

import com.bablsoft.accessflow.api.internal.UpdateCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Install-level release update status. Deliberately gated on authentication only — being behind
 * the latest release is an operational fact every signed-in user may see, and the answer carries
 * no organization data.
 */
@RestController
@RequestMapping("/api/v1/system/update-status")
@PreAuthorize("isAuthenticated()")
@Tag(name = "System", description = "Install-level information available to every signed-in user")
@RequiredArgsConstructor
class SystemUpdateStatusController {

    private final UpdateCheckService updateCheckService;

    @GetMapping
    @Operation(summary = "Return whether a newer stable AccessFlow release than the running build exists")
    @ApiResponse(responseCode = "200", description = "Cached update snapshot; never blocks on the network")
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated")
    SystemUpdateStatusResponse get() {
        return SystemUpdateStatusResponse.from(updateCheckService.status());
    }
}
