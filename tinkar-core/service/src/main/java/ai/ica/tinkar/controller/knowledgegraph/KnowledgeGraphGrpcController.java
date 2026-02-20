package ai.ica.tinkar.controller.knowledgegraph;

import ai.ica.tinkar.dto.PremiseType;
import ai.ica.tinkar.proto.*;
import ai.ica.tinkar.service.CoordinateFactory;
import ai.ica.tinkar.service.TinkarService;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.schema.PublicId;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.List;

/**
 * Tier 2: Concept-Aware (Knowledge Graph) — gRPC controller.
 *
 * Exposes the concept-oriented structure with semantic patterns, STAMP info,
 * and version history. Supports optional coordinate overrides for STAMP filtering
 * and navigation mode. Target audience: analytics engineers, knowledge graph practitioners.
 */
@GrpcService
@Slf4j
public class KnowledgeGraphGrpcController extends IkeKnowledgeGraphGrpc.IkeKnowledgeGraphImplBase {

    private final TinkarService tinkarService;

    public KnowledgeGraphGrpcController(TinkarService tinkarService) {
        this.tinkarService = tinkarService;
    }

    @Override
    public void getConceptSemantics(KnowledgeGraphConceptRequest request,
            StreamObserver<TinkarConceptSemanticsResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("IkeKnowledgeGraph getConceptSemantics request for conceptId: {}", conceptId);
        ViewCalculatorWithCache calc = buildCalculator(request.getCoordinateOverride());
        responseObserver.onNext(tinkarService.getConceptSemanticsProto(conceptId, calc));
        responseObserver.onCompleted();
    }

    @Override
    public void getChildConcepts(KnowledgeGraphConceptRequest request,
            StreamObserver<TinkarSearchQueryResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("IkeKnowledgeGraph getChildConcepts request for conceptId: {}", conceptId);
        ViewCalculatorWithCache calc = buildCalculator(request.getCoordinateOverride());
        responseObserver.onNext(tinkarService.getChildConcepts(conceptId, calc));
        responseObserver.onCompleted();
    }

    @Override
    public void getDescendantConcepts(KnowledgeGraphConceptRequest request,
            StreamObserver<TinkarSearchQueryResponse> responseObserver) {
        String conceptId = extractConceptId(request.getPublicId());
        log.info("IkeKnowledgeGraph getDescendantConcepts request for conceptId: {}", conceptId);
        ViewCalculatorWithCache calc = buildCalculator(request.getCoordinateOverride());
        responseObserver.onNext(tinkarService.getDescendantConcepts(conceptId, calc));
        responseObserver.onCompleted();
    }

    private ViewCalculatorWithCache buildCalculator(ai.ica.tinkar.proto.CoordinateOverride protoOverride) {
        if (protoOverride == null || protoOverride.equals(ai.ica.tinkar.proto.CoordinateOverride.getDefaultInstance())) {
            return CoordinateFactory.defaultCalculator();
        }

        String allowedStates = switch (protoOverride.getAllowedStates()) {
            case ACTIVE_ONLY -> "ACTIVE";
            case INACTIVE_ONLY -> "INACTIVE";
            default -> null; // ACTIVE_AND_INACTIVE is default, treat as null
        };

        Long positionTime = protoOverride.getPositionTime() != 0 ? protoOverride.getPositionTime() : null;
        String positionPathId = protoOverride.getPositionPathId().isEmpty() ? null : protoOverride.getPositionPathId();
        List<String> moduleIds = protoOverride.getModuleIdsList().isEmpty() ? null : protoOverride.getModuleIdsList();
        List<String> excludedModuleIds = protoOverride.getExcludedModuleIdsList().isEmpty() ? null : protoOverride.getExcludedModuleIdsList();

        PremiseType premiseType = switch (protoOverride.getPremiseType()) {
            case STATED -> PremiseType.STATED;
            default -> null; // INFERRED is default, treat as null
        };

        ai.ica.tinkar.dto.CoordinateOverride dtoOverride = new ai.ica.tinkar.dto.CoordinateOverride(
                allowedStates, positionTime, positionPathId, moduleIds, excludedModuleIds, premiseType);
        return CoordinateFactory.buildCalculator(dtoOverride);
    }

    private String extractConceptId(PublicId publicId) {
        if (publicId == null || publicId.getUuidsList().isEmpty()) {
            return "";
        }
        return publicId.getUuids(0);
    }
}
