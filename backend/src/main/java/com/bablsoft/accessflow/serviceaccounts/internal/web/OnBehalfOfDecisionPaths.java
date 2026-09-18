package com.bablsoft.accessflow.serviceaccounts.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

/**
 * The review / decision surfaces on which {@code X-AccessFlow-On-Behalf-Of} is rejected outright
 * (#874): an agent may act <em>for</em> a human when it submits; it may never cast a vote
 * <em>as</em> one. Any method, not only the decision {@code POST}s — a read with the header is
 * meaningless and a misconfigured agent should learn that immediately. Matched with the same
 * {@link PathPatternRequestMatcher} family the security chain uses, so path normalisation is
 * identical. Request groups share their base path with submission, hence the two narrow patterns.
 */
public final class OnBehalfOfDecisionPaths {

    static final List<String> PATTERNS = List.of(
            "/api/v1/reviews/**",                    // query reviews, bulk, and /reviews/attestations
            "/api/v1/api-reviews/**",
            "/api/v1/deployment-reviews/**",
            "/api/v1/deployment-rollback-reviews/**",
            "/api/v1/request-groups/*/approve",
            "/api/v1/request-groups/*/reject",
            "/api/v1/lifecycle/erasure-reviews/**",
            "/api/v1/admin/access-requests/**",
            "/api/v1/admin/break-glass/**");

    private final RequestMatcher matcher;

    public OnBehalfOfDecisionPaths() {
        var builder = PathPatternRequestMatcher.withDefaults();
        this.matcher = new OrRequestMatcher(PATTERNS.stream()
                .map(builder::matcher)
                .map(RequestMatcher.class::cast)
                .toList());
    }

    boolean isDecisionPath(HttpServletRequest request) {
        return matcher.matches(request);
    }
}
