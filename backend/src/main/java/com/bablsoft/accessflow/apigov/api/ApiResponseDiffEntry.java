package com.bablsoft.accessflow.apigov.api;

/** One differing path; {@code before} / {@code after} are null on the side the path is absent (#887). */
public record ApiResponseDiffEntry(String path, ApiResponseDiffKind kind, String before, String after) {
}
