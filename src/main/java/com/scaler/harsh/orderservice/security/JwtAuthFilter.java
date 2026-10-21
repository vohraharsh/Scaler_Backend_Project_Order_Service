package com.scaler.harsh.orderservice.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Closes the "hit the service directly on its own port" gap — Kong already JWT-gates every
 * /orders route, so this filter has no public paths of its own: everything under /orders
 * requires a valid JWT. The same incoming token is then forwarded by OrderServiceImpl on its
 * own internal calls to User/Cart/Product/Payment Service, so placing an order still works.
 *
 * Gap #4 (logout): after signature/expiry pass, also checks the same Redis instance Auth
 * Service writes to on logout. A token can be structurally valid and unexpired yet still
 * rejected if the user logged out before it would have naturally expired.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    // Must match the prefix Auth Service's SelfAuthService.logout writes under.
    private static final String REVOKED_TOKEN_PREFIX = "revoked-token:";

    private final SecretKey key;
    private final StringRedisTemplate redisTemplate;

    public JwtAuthFilter(@Value("${jwt.secret}") String secret, StringRedisTemplate redisTemplate) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response, "Missing or malformed Authorization header");
            return;
        }

        String token = header.substring(7);
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        } catch (ExpiredJwtException e) {
            reject(response, "Token expired");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            reject(response, "Invalid token");
            return;
        }

        if (Boolean.TRUE.equals(redisTemplate.hasKey(REVOKED_TOKEN_PREFIX + token))) {
            reject(response, "Token has been logged out");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}