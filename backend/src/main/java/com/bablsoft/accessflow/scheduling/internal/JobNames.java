package com.bablsoft.accessflow.scheduling.internal;

import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place a job's name and module are derived, so the recorder and the registry agree.
 *
 * <p>A job is named after its class — every AccessFlow job is one {@code @Scheduled} method per
 * class. A class declaring more than one (a third-party scheduler bean, say) is qualified as
 * {@code Class#method}, so its methods do not collapse into one row and one history.
 */
final class JobNames {

    private static final String ROOT_PACKAGE = "com.bablsoft.accessflow.";
    private static final Map<Class<?>, Integer> SCHEDULED_METHOD_COUNTS = new ConcurrentHashMap<>();

    private JobNames() {
    }

    static String of(Class<?> targetClass, Method method) {
        var count = SCHEDULED_METHOD_COUNTS.computeIfAbsent(targetClass, JobNames::countScheduledMethods);
        return count > 1
                ? targetClass.getSimpleName() + "#" + method.getName()
                : targetClass.getSimpleName();
    }

    static String moduleOf(Class<?> targetClass) {
        var name = targetClass.getName();
        if (!name.startsWith(ROOT_PACKAGE)) {
            return null;
        }
        var rest = name.substring(ROOT_PACKAGE.length());
        var dot = rest.indexOf('.');
        return dot < 0 ? null : rest.substring(0, dot);
    }

    private static int countScheduledMethods(Class<?> type) {
        return MethodIntrospector.selectMethods(type, (MethodIntrospector.MetadataLookup<Boolean>) method ->
                AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)
                        || AnnotatedElementUtils.hasAnnotation(method, Schedules.class) ? Boolean.TRUE : null)
                .size();
    }
}
