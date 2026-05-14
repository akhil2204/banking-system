package com.banking.authservice.service.impl;

import com.banking.authservice.entity.Credential;
import com.banking.authservice.service.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;

@Service
public class JwtServiceImpl implements JwtService {

    private final SecretKey signingKey;
    private final long expirationHours;

    /*
     * Constructor injection reads jwt.secret and jwt.expiration-hours from
     * application.yml. The secret is base64-encoded in config; we decode it
     * to bytes and build an HMAC-SHA256 key.
     *
     * Why HMAC-SHA256 (HS256)?
     *   - Symmetric: the same key signs and verifies. Both auth-service and
     *     api-gateway share JWT_SECRET. Simple and fast.
     *   - For production with multiple teams or external verifiers, use RS256
     *     (asymmetric): auth-service holds the private key; everyone else uses
     *     the public key. This way the private key never leaves auth-service.
     *
     * Key size requirement: HS256 requires at least 256 bits (32 bytes).
     * Keys.hmacShaKeyFor() throws WeakKeyException if the key is too short.
     */
    public JwtServiceImpl(
            @Value("${jwt.secret}") String base64Secret,
            @Value("${jwt.expiration-hours}") long expirationHours) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Secret);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationHours = expirationHours;
    }

    @Override
    public String generate(Credential credential) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expirationHours * 3_600_000L);

        return Jwts.builder()
                /*
                 * sub (subject): the principal this token represents.
                 * We use userId as a string — it's the stable, unchanging identity
                 * that downstream services use to identify the caller.
                 */
                .subject(credential.getUserId().toString())
                /*
                 * Custom claims — extra data attached to the token.
                 * Downstream services can read these from headers forwarded by the gateway.
                 * Keep claims minimal: large tokens waste bandwidth on every request.
                 */
                .claim("email", credential.getEmail())
                .claim("role", credential.getRole().name())
                .issuedAt(now)
                .expiration(expiry)
                /*
                 * signWith(key): JJWT 0.12.x infers the algorithm from the key type.
                 * SecretKey from Keys.hmacShaKeyFor() → HS256 automatically.
                 * No need to specify SignatureAlgorithm.HS256 explicitly.
                 */
                .signWith(signingKey)
                .compact();
    }

    @Override
    public LocalDateTime expiresAt() {
        return LocalDateTime.now().plusHours(expirationHours);
    }
}
