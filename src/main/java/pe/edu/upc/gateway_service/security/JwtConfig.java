package pe.edu.upc.gateway_service.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;

import java.util.List;

@Configuration
public class JwtConfig {

    @Bean
    public ReactiveJwtDecoder auth0JwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:${spring.security.oauth2.resource-server.jwt.issuer-uri}}") String issuerUri,
            @Value("${auth0.audience}") String audience) {
        var jwtDecoder = NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new AudienceValidator(audience)
        ));
        return jwtDecoder;
    }

    @Bean
    public ReactiveJwtDecoder legacyJwtDecoder(
            @Value("${legacy.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${legacy.jwt.issuer:iam-service}") String issuer) {
        var jwtDecoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return jwtDecoder;
    }

    @Bean
    @Primary
    public ReactiveJwtDecoder reactiveJwtDecoder(
            @Qualifier("auth0JwtDecoder") ReactiveJwtDecoder auth0JwtDecoder,
            @Qualifier("legacyJwtDecoder") ReactiveJwtDecoder legacyJwtDecoder,
            @Value("${legacy.jwt.enabled:true}") boolean legacyJwtEnabled) {
        return token -> {
            if (!legacyJwtEnabled) {
                return auth0JwtDecoder.decode(token)
                        .onErrorMap(error -> jwtFailure(null, error));
            }
            return legacyJwtDecoder.decode(token)
                    .onErrorResume(legacyFailure -> auth0JwtDecoder.decode(token)
                            .onErrorMap(auth0Failure -> jwtFailure(legacyFailure, auth0Failure)));
        };
    }

    private static RuntimeException jwtFailure(Throwable legacyFailure, Throwable auth0Failure) {
        if (legacyFailure instanceof JwtException jwtException) {
            return jwtException;
        }
        if (auth0Failure instanceof JwtException jwtException) {
            return jwtException;
        }
        var failure = new BadJwtException("Failed to decode JWT with IAM JWKS and Auth0");
        if (legacyFailure != null) {
            failure.addSuppressed(legacyFailure);
        }
        failure.addSuppressed(auth0Failure);
        return failure;
    }

    private static final class AudienceValidator implements OAuth2TokenValidator<Jwt> {
        private final String audience;
        private final OAuth2Error error = new OAuth2Error(
                "invalid_token",
                "The required audience is missing",
                null
        );

        private AudienceValidator(String audience) {
            this.audience = audience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            var audiences = token.getAudience();
            if (audiences == null) {
                audiences = List.of();
            }
            return audiences.contains(audience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(error);
        }
    }
}
