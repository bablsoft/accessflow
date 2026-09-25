package com.bablsoft.accessflow.core.api;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The one matcher behind a permission's {@code denied_shapes} (#940), shared by every gate that
 * refuses a query by its structure — submission, the recurring recheck, break-glass, dry-run,
 * request groups and the access simulator — so they can never disagree.
 */
public final class DeniedShapes {

    private DeniedShapes() {
    }

    /** Drops nulls and duplicates; the result is in declaration order. */
    public static List<QueryShape> normalize(Collection<QueryShape> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        var out = EnumSet.noneOf(QueryShape.class);
        for (QueryShape shape : raw) {
            if (shape != null) {
                out.add(shape);
            }
        }
        return List.copyOf(out);
    }

    /** Reads stored shape names (the {@code denied_shapes} TEXT[] column) back into shapes. */
    public static List<QueryShape> fromNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        var out = EnumSet.noneOf(QueryShape.class);
        for (String name : names) {
            out.add(QueryShape.valueOf(name));
        }
        return List.copyOf(out);
    }

    /** The shape names to store, in declaration order; {@code null} when nothing is denied. */
    public static String[] toNames(Collection<QueryShape> shapes) {
        var normalized = normalize(shapes);
        return normalized.isEmpty() ? null
                : normalized.stream().map(QueryShape::name).toArray(String[]::new);
    }

    /** Union of two deny-lists: a denial from either side survives. */
    public static List<QueryShape> union(Collection<QueryShape> left, Collection<QueryShape> right) {
        var out = EnumSet.noneOf(QueryShape.class);
        out.addAll(normalize(left));
        out.addAll(normalize(right));
        return List.copyOf(out);
    }

    /**
     * @return the denied shapes the parsed query has, in declaration order; empty when it has none.
     *         A query whose shape was not analyzed (a non-JSqlParser engine, or an AST the detector
     *         could not walk) has every denied shape, so a deny-list can never be silently skipped.
     *         {@code OTHER} is never checked, since no permission grants it.
     */
    public static SortedSet<QueryShape> rejected(Collection<QueryShape> rawDenied, SqlParseResult parsed) {
        var denied = normalize(rawDenied);
        var out = new TreeSet<QueryShape>();
        if (denied.isEmpty() || parsed == null || parsed.type() == QueryType.OTHER) {
            return out;
        }
        if (!parsed.shapesAnalyzed()) {
            out.addAll(denied);
            return out;
        }
        for (QueryShape shape : denied) {
            if (parsed.shapes().contains(shape)) {
                out.add(shape);
            }
        }
        return out;
    }
}
