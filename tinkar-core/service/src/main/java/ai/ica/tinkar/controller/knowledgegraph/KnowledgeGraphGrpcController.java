package ai.ica.tinkar.controller.knowledgegraph;

import ai.ica.tinkar.proto.*;
import ai.ica.tinkar.service.TinkarService;
import dev.ikm.tinkar.schema.PublicId;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * Tier 2: Concept-Aware (Knowledge Graph) — gRPC controller.
 *
 * Exposes the concept-oriented structure with semantic patterns, STAMP info,
 * and version history. Target audience: analytics engineers, knowledge graph practitioners.
 */
@GrpcService
@Slf4j
public class KnowledgeGraphGrpcController extends IkeKnowledgeGraphGrpc.IkeKnowledgeGraphImplBase {

    private final TinkarService tinkarService;

    public KnowledgeGraphGrpcController(TinkarService tinkarService) {
        this.tinkarService = tinkarService;
    }

    @Override
    public void getConceptSemantics(TinkarConceptIdRequest request,
            StreamObserver<TinkarConceptSemanticsResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("IkeKnowledgeGraph getConceptSemantics request for conceptId: {}", conceptId);
        responseObserver.onNext(tinkarService.getConceptSemanticsProto(conceptId));
        responseObserver.onCompleted();
    }

    private String extractConceptId(PublicId publicId) {
        if (publicId == null || publicId.getUuidsList().isEmpty()) {
            return "";
        }
        return publicId.getUuids(0);
    }
}
