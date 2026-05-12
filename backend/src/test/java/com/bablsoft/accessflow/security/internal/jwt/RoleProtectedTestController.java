package com.bablsoft.accessflow.security.internal.jwt;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "accessflow.test.method-security", havingValue = "true")
public class RoleProtectedTestController {

    @GetMapping("/test/method-security/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String admin() {
        return "ok";
    }

    @GetMapping("/test/method-security/reviewer")
    @PreAuthorize("hasRole('REVIEWER')")
    public String reviewer() {
        return "ok";
    }

    @GetMapping("/test/method-security/analyst")
    @PreAuthorize("hasRole('ANALYST')")
    public String analyst() {
        return "ok";
    }

    @GetMapping("/test/method-security/readonly")
    @PreAuthorize("hasRole('READONLY')")
    public String readonly() {
        return "ok";
    }
}
