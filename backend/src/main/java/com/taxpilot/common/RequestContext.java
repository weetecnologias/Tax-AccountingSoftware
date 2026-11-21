package com.taxpilot.common;

import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/** Access to the current HTTP request's client metadata, safe to call outside a request. */
public final class RequestContext {

    private RequestContext() {
    }

    public static String clientIp() {
        HttpServletRequest req = current();
        return req == null ? null : truncate(req.getRemoteAddr(), 45);
    }

    public static String userAgent() {
        HttpServletRequest req = current();
        return req == null ? null : truncate(req.getHeader("User-Agent"), 300);
    }

    public static String requestId() {
        return MDC.get(RequestIdFilter.MDC_KEY);
    }

    private static HttpServletRequest current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
