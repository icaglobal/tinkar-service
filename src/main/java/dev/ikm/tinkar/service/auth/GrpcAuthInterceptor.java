package dev.ikm.tinkar.service.auth;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Checks the token on every gRPC call when authentication is on, as the REST filter chain does
 * for REST requests.
 *
 * <p>A call carries its token as {@code authorization: Bearer <token>} metadata. A call without
 * one, or with one that is not accepted, is closed with {@link Status#UNAUTHENTICATED} before it
 * reaches a service. An accepted call runs with its author in the gRPC {@link Context}, where
 * {@link CurrentAuthor} finds it. gRPC's own services, health and reflection, stay open.
 *
 * <p>A plain gRPC interceptor rather than the gRPC starter's Spring Security integration: that
 * integration predates Spring Security 7, which this service runs on.
 */
public final class GrpcAuthInterceptor implements ServerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcAuthInterceptor.class);

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final String BEARER = "Bearer ";

    private final boolean enabled;
    private final TokenService tokens;

    public GrpcAuthInterceptor(boolean enabled, TokenService tokens) {
        this.enabled = enabled;
        this.tokens = tokens;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        if (!enabled || call.getMethodDescriptor().getServiceName() == null
                || call.getMethodDescriptor().getServiceName().startsWith("grpc.")) {
            return next.startCall(call, headers);
        }
        String authorization = headers.get(AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return refuse(call, "A token is required: sign in, then send it as 'authorization: Bearer <token>'");
        }
        Jwt jwt;
        try {
            jwt = tokens.check(authorization.substring(BEARER.length()).strip());
        } catch (JwtException e) {
            LOG.debug("Refused {}: {}", call.getMethodDescriptor().getFullMethodName(), e.getMessage());
            return refuse(call, "The token was not accepted: " + e.getMessage());
        }
        Context context = Context.current().withValue(CurrentAuthor.GRPC_AUTHOR_ID, jwt.getSubject());
        return Contexts.interceptCall(context, call, headers, next);
    }

    private static <Q, R> ServerCall.Listener<Q> refuse(ServerCall<Q, R> call, String why) {
        call.close(Status.UNAUTHENTICATED.withDescription(why), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
