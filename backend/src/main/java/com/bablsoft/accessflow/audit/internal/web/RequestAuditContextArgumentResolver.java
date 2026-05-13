package com.bablsoft.accessflow.audit.internal.web;

import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
class RequestAuditContextArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String USER_AGENT = "User-Agent";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return RequestAuditContext.class.equals(parameter.getParameterType());
    }

    @Override
    public RequestAuditContext resolveArgument(MethodParameter parameter,
                                               ModelAndViewContainer mavContainer,
                                               NativeWebRequest webRequest,
                                               WebDataBinderFactory binderFactory) {
        var request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            return new RequestAuditContext(null, null);
        }
        return new RequestAuditContext(extractIp(request), request.getHeader(USER_AGENT));
    }

    private static String extractIp(HttpServletRequest request) {
        var forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            var first = forwarded.split(",", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
