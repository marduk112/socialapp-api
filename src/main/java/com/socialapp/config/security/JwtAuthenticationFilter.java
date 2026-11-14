package com.socialapp.config.security;

import com.socialapp.usermanagement.infrastructure.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates requests carrying {@code Authorization: Bearer <jwt>}.
 * <p>
 * For the SSE endpoint only ({@value #SSE_PATH}, GET), the token may also be passed as the
 * {@value #QUERY_TOKEN_PARAM} query parameter, because the browser {@code EventSource} API
 * cannot send headers.
 * <p>
 * An invalid/expired token does not short-circuit the request: the request simply stays
 * anonymous, so public endpoints (login/register) keep working with a stale token and
 * protected endpoints are rejected with 401 by {@link JsonAuthenticationEntryPoint}.
 * <p>
 * Not a Spring bean on purpose — registering it as a {@code @Component} would also add it
 * to the plain servlet filter chain.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    static final String SSE_PATH = "/api/v1/feed/stream";
    static final String QUERY_TOKEN_PARAM = "access_token";
    static final String INVALID_TOKEN_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".INVALID_TOKEN";
    private static final String BEARER_PREFIX = "bearer ";

    private final JwtService jwtService;
    private final SecurityContextHolderStrategy contextHolderStrategy = SecurityContextHolder.getContextHolderStrategy();
    private final WebAuthenticationDetailsSource detailsSource = new WebAuthenticationDetailsSource();

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && contextHolderStrategy.getContext().getAuthentication() == null) {
            jwtService.parse(token).ifPresentOrElse(principal -> {
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, AuthorityUtils.createAuthorityList("ROLE_USER"));
                authentication.setDetails(detailsSource.buildDetails(request));
                var context = contextHolderStrategy.createEmptyContext();
                context.setAuthentication(authentication);
                contextHolderStrategy.setContext(context);
            }, () -> request.setAttribute(INVALID_TOKEN_ATTRIBUTE, Boolean.TRUE));
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        if (isSseRequest(request)) {
            String token = request.getParameter(QUERY_TOKEN_PARAM);
            return token == null || token.isBlank() ? null : token.trim();
        }
        return null;
    }

    private static boolean isSseRequest(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return SSE_PATH.equals(path);
    }
}
