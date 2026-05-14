package com.banking.apigateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/*
 * GlobalFilter runs on every request that enters the gateway, before any
 * route filter. Ordered.HIGHEST_PRECEDENCE ensures this filter runs before
 * the built-in routing filters — a request with an invalid token never reaches
 * a downstream service.
 *
 * This is a WebFlux (reactive) filter — all I/O must be non-blocking.
 * JWT validation is CPU-bound (no I/O) so it's safe to run on the event loop.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final SecretKey signingKey;

    /*
     * Paths that do NOT require a JWT token.
     * /v1/auth/**  — register and login (these endpoints issue the token)
     * /actuator/** — internal health probes from Docker / load balancers
     *
     * All other /v1/** paths require a valid Bearer token.
     */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/v1/auth/",
            "/actuator"
    );

    public JwtAuthenticationFilter(@Value("${jwt.secret}") String base64Secret) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Secret);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Skip token check for public paths
        if (isPublic(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Missing or malformed Authorization header for path: {}", path);
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7); // strip "Bearer "

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            /*
             * Token is valid. Forward the decoded user identity as headers to
             * downstream services. Services can read X-User-Id, X-User-Email,
             * X-User-Role without re-validating the JWT — the gateway is the
             * single point of trust.
             *
             * IMPORTANT: Downstream services must NOT accept these headers from
             * external callers. The gateway strips them on inbound requests
             * (or you block external access to internal ports).
             * For now we add them; stripping inbound user headers is a future
             * hardening step.
             */
            String userId    = claims.getSubject();
            String email     = claims.get("email", String.class);
            String role      = claims.get("role",  String.class);

            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header("X-User-Id",    userId)
                    .header("X-User-Email", email != null ? email : "")
                    .header("X-User-Role",  role  != null ? role  : "USER")
                    .build();

            log.debug("JWT validated: userId={} role={} path={}", userId, role, path);
            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (JwtException e) {
            log.warn("JWT validation failed for path {}: {}", path, e.getMessage());
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Token is invalid or has expired");
        }
    }

    @Override
    public int getOrder() {
        // Run before all other filters — highest possible priority
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private boolean isPublic(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    /*
     * Write a JSON 401/403 response directly without forwarding to any service.
     * We build the standard error envelope manually because we have no
     * @RestControllerAdvice in a WebFlux gateway — the filter handles its own errors.
     */
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = String.format(
                "{\"timestamp\":\"%s\",\"status\":%d,\"error\":\"%s\",\"message\":\"%s\",\"path\":\"%s\"}",
                LocalDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                exchange.getRequest().getURI().getPath()
        );

        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
