package dev.ikm.tinkar.service.auth;

import dev.ikm.tinkar.terms.KernelConcept;
import io.grpc.Context;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * The author a request signed in as, which the service stamps its writes with.
 *
 * <p>A REST request's author is in Spring Security's context, put there from its token; a gRPC
 * call's is in the gRPC {@link Context}, put there by {@link GrpcAuthInterceptor}. Without
 * authentication there is none, and writes are stamped with the generic {@link KernelConcept#USER},
 * as they always were.
 */
public final class CurrentAuthor {

    /** The signed-in author's UUID, during a gRPC call. */
    static final Context.Key<String> GRPC_AUTHOR_ID = Context.key("tinkar-author-id");

    private CurrentAuthor() {
    }

    /**
     * The signed-in author's UUID, as the token's subject holds it.
     *
     * @return the UUID, or empty when the request has no signed-in author
     */
    public static Optional<String> id() {
        String grpcAuthor = GRPC_AUTHOR_ID.get();
        if (grpcAuthor != null) {
            return Optional.of(grpcAuthor);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return Optional.ofNullable(jwt.getSubject());
        }
        return Optional.empty();
    }

    /**
     * The nid to stamp a write with: the signed-in author's, else the generic user's.
     *
     * <p>A token names an author that existed when it was issued; if the knowledge base no longer
     * holds it, the write is stamped with the generic user rather than with an id it cannot name.
     */
    public static long nidForStamp() {
        return id().map(Authors::nidOf).filter(OptionalLong::isPresent).map(OptionalLong::getAsLong)
                .orElse(KernelConcept.USER.nid());
    }
}
