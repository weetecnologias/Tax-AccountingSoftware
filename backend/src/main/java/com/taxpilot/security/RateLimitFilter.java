package com.taxpilot.security;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Fixed-window per-IP limit on unauthenticated auth endpoints.
 * In-process only; a Redis-backed limiter replaces it when the app runs on more than one node (backlog).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED = Set.of("/api/v1/auth/login", "/api/v1/auth/refresh");

    private final int limitPerMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    private record Window(long minute, AtomicInteger count) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!LIMITED.contains(request.getRequestURI()) || !"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        long minute = System.currentTimeMillis() / 60_000;
        String key = request.getRemoteAddr() + "|" + request.getRequestURI();
        Window w = windows.compute(key, (k, existing) ->
                existing == null || existing.minute() != minute ? new Window(minute, new AtomicInteger()) : existing);
        if (w.count().incrementAndGet() > limitPerMinute) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":429,\"title\":\"Too Many Requests\",\"code\":\"RATE_LIMITED\","
                    + "\"detail\":\"Too many attempts. Wait a minute and try again.\"}");
            return;
        }
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
        }
        chain.doFilter(request, response);
    }
}
