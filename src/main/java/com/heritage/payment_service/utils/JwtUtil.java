package com.heritage.payment_service.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;

@Component
public class JwtUtil {

    @Value("${jwt.access.secret}")
    private String accessSecret;

    @Value("${jwt.issuer}")
    private String issuer;

    public Claims validateToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(
                Base64.getDecoder().decode(accessSecret)
        );

        return Jwts.parserBuilder()
                .setSigningKey(key)
                .requireIssuer(issuer)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public Long extractUserId(String authHeader) {
        String token = authHeader.startsWith("Bearer ")
                ? authHeader.substring(7)
                : authHeader;
        Claims claims = validateToken(token);
        return Long.parseLong(claims.getSubject());
    }
}
