package com.bablsoft.accessflow.ai.api;

/**
 * Kind of optimization the AI analyzer proposes for a submitted query:
 * {@code INDEX} for an index-definition statement, {@code REWRITE} for an equivalent but more
 * efficient version of the query itself.
 */
public enum OptimizationType {
    INDEX,
    REWRITE
}
