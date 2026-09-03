package com.bablsoft.accessflow.api.internal;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A parsed {@code MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]} version. Comparison is numeric per
 * component — {@code 2.10.0} is newer than {@code 2.9.0} — and a pre-release sorts below the
 * release it precedes. Two pre-release identifiers are compared as plain strings (so
 * {@code beta.10} sorts below {@code beta.2}); the update check never ranks pre-releases against
 * each other, so full SemVer §11 precedence is deliberately not implemented. Pure and Spring-free
 * so the decision rule is unit-testable.
 */
public record SemanticVersion(int major, int minor, int patch, String preRelease)
        implements Comparable<SemanticVersion> {

    private static final Pattern PATTERN = Pattern.compile(
            "^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z][0-9A-Za-z.-]*))?(?:\\+[0-9A-Za-z.-]+)?$");

    /** Parses a version string; empty for {@code null}, blank, or anything that is not semver. */
    public static Optional<SemanticVersion> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        var matcher = PATTERN.matcher(raw.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SemanticVersion(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    matcher.group(4)));
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }

    /** True for {@code 1.0.0-SNAPSHOT}, {@code 2.5.0-beta.1}, {@code 1.0.0-rc.1} and the like. */
    public boolean isPreRelease() {
        return preRelease != null;
    }

    public boolean isNewerThan(SemanticVersion other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        if (byMinor != 0) {
            return byMinor;
        }
        int byPatch = Integer.compare(patch, other.patch);
        if (byPatch != 0) {
            return byPatch;
        }
        if (preRelease == null) {
            return other.preRelease == null ? 0 : 1;
        }
        if (other.preRelease == null) {
            return -1;
        }
        return preRelease.compareTo(other.preRelease);
    }
}
