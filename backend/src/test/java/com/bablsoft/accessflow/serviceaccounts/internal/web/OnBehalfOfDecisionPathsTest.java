package com.bablsoft.accessflow.serviceaccounts.internal.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class OnBehalfOfDecisionPathsTest {

    private final OnBehalfOfDecisionPaths paths = new OnBehalfOfDecisionPaths();

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/reviews",
            "/api/v1/reviews/8f1c1c1c-0000-0000-0000-000000000001/approve",
            "/api/v1/reviews/bulk",
            "/api/v1/reviews/attestations/items/bulk",
            "/api/v1/api-reviews/8f1c1c1c-0000-0000-0000-000000000001/reject",
            "/api/v1/deployment-reviews/8f1c1c1c-0000-0000-0000-000000000001/approve",
            "/api/v1/deployment-rollback-reviews/8f1c1c1c-0000-0000-0000-000000000001/acknowledge",
            "/api/v1/request-groups/8f1c1c1c-0000-0000-0000-000000000001/approve",
            "/api/v1/request-groups/8f1c1c1c-0000-0000-0000-000000000001/reject",
            "/api/v1/lifecycle/erasure-reviews/8f1c1c1c-0000-0000-0000-000000000001/approve",
            "/api/v1/admin/access-requests/8f1c1c1c-0000-0000-0000-000000000001/approve",
            "/api/v1/admin/break-glass/8f1c1c1c-0000-0000-0000-000000000001/acknowledge"})
    void decisionPathsAreRecognised(String path) {
        assertThat(paths.isDecisionPath(request(path))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/queries",
            "/api/v1/queries/8f1c1c1c-0000-0000-0000-000000000001/replay",
            "/api/v1/api-requests",
            "/api/v1/deployment-requests",
            "/api/v1/request-groups",
            "/api/v1/request-groups/8f1c1c1c-0000-0000-0000-000000000001/submit",
            "/api/v1/request-groups/8f1c1c1c-0000-0000-0000-000000000001",
            "/api/v1/me",
            "/mcp"})
    void submissionAndReadPathsAreNot(String path) {
        assertThat(paths.isDecisionPath(request(path))).isFalse();
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        return request;
    }
}
