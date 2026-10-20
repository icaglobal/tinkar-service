package dev.ikm.tinkar.service.auth;

import dev.ikm.tinkar.service.proto.IkeKnowledgeGraphGrpc;
import dev.ikm.tinkar.service.proto.ListPatternsRequest;
import dev.ikm.tinkar.service.proto.ListPatternsResponse;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * gRPC calls with authentication on: refused without an accepted token, and run as the token's
 * author with one. Calls go through a real server, on a free local port, and a stub. (Not the
 * in-process transport: the gRPC starter brings an older grpc-inprocess than the gRPC this
 * service runs, and the two do not work together.)
 */
class GrpcAuthInterceptorTest {

    private static final String SECRET = "a-secret-of-at-least-thirty-two-bytes-for-tests";

    private static final Authors.Author AUTHOR = new Authors.Author(
            "11111111-2222-3333-4444-555555555555",
            List.of(UUID.fromString("11111111-2222-3333-4444-555555555555")), "Test author", 42L);

    private final TokenService tokens = new TokenService(SECRET, Duration.ofHours(1), Clock.systemUTC());

    private Server server;
    private ManagedChannel channel;

    /** Answers with the author the call runs as, in the error message field. */
    private static final class WhoAmI extends IkeKnowledgeGraphGrpc.IkeKnowledgeGraphImplBase {
        @Override
        public void listPatterns(ListPatternsRequest request, StreamObserver<ListPatternsResponse> observer) {
            observer.onNext(ListPatternsResponse.newBuilder()
                    .setSuccess(true)
                    .setErrorMessage(CurrentAuthor.id().orElse("nobody"))
                    .build());
            observer.onCompleted();
        }
    }

    private IkeKnowledgeGraphGrpc.IkeKnowledgeGraphBlockingStub stub(boolean enabled, String authorization)
            throws IOException {
        server = NettyServerBuilder.forAddress(new InetSocketAddress("localhost", 0))
                .addService(ServerInterceptors.intercept(new WhoAmI(), new GrpcAuthInterceptor(enabled, tokens)))
                .build().start();
        channel = NettyChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        IkeKnowledgeGraphGrpc.IkeKnowledgeGraphBlockingStub stub = IkeKnowledgeGraphGrpc.newBlockingStub(channel);
        if (authorization != null) {
            Metadata headers = new Metadata();
            headers.put(GrpcAuthInterceptor.AUTHORIZATION, authorization);
            stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        }
        return stub;
    }

    @AfterEach
    void stop() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    void aCallWithAnAcceptedTokenRunsAsItsAuthor() throws IOException {
        String token = tokens.issue(AUTHOR).token();

        ListPatternsResponse response = stub(true, "Bearer " + token).listPatterns(ListPatternsRequest.getDefaultInstance());

        assertThat(response.getErrorMessage()).isEqualTo(AUTHOR.id());
    }

    @Test
    void aCallWithoutATokenIsRefused() throws IOException {
        var stub = stub(true, null);

        assertThatThrownBy(() -> stub.listPatterns(ListPatternsRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e ->
                        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void aCallWithATokenThatIsNotAcceptedIsRefused() throws IOException {
        String forged = new TokenService("another-secret-of-at-least-thirty-two-bytes", Duration.ofHours(1),
                Clock.systemUTC()).issue(AUTHOR).token();
        var forgedStub = stub(true, "Bearer " + forged);

        assertThatThrownBy(() -> forgedStub.listPatterns(ListPatternsRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e ->
                        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void aTokenWithoutTheBearerPrefixIsRefused() throws IOException {
        var stub = stub(true, tokens.issue(AUTHOR).token());

        assertThatThrownBy(() -> stub.listPatterns(ListPatternsRequest.getDefaultInstance()))
                .isInstanceOf(StatusRuntimeException.class);
    }

    @Test
    void withAuthenticationOffEveryCallRunsAsNobody() throws IOException {
        ListPatternsResponse response = stub(false, null).listPatterns(ListPatternsRequest.getDefaultInstance());

        assertThat(response.getErrorMessage()).isEqualTo("nobody");
    }
}
