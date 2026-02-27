package ai.ica.tinkar.controller.knowledgegraph;

import ai.ica.tinkar.dto.PremiseType;
import ai.ica.tinkar.proto.*;
import ai.ica.tinkar.service.CoordinateFactory;
import ai.ica.tinkar.service.CoordinateStoreService;
import ai.ica.tinkar.service.TinkarService;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.schema.PublicId;
import io.grpc.Status;
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
    private final CoordinateStoreService coordinateStoreService;

    public KnowledgeGraphGrpcController(TinkarService tinkarService, CoordinateStoreService coordinateStoreService) {
        this.tinkarService = tinkarService;
        this.coordinateStoreService = coordinateStoreService;
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

    @Override
    public void saveCoordinate(SaveCoordinateRequest request,
            StreamObserver<SaveCoordinateResponse> responseObserver) {
        log.info("IkeKnowledgeGraph saveCoordinate request for name: {}", request.getName());
        ai.ica.tinkar.dto.CoordinateOverride settings = protoToDto(
                request.hasSettings() ? request.getSettings() : null);
        ai.ica.tinkar.dto.SavedCoordinateRequest dtoRequest =
                new ai.ica.tinkar.dto.SavedCoordinateRequest(request.getName(), settings);
        ai.ica.tinkar.dto.SavedCoordinateResponse dto = coordinateStoreService.save(dtoRequest);
        responseObserver.onNext(toSaveCoordinateResponse(dto));
        responseObserver.onCompleted();
    }

    @Override
    public void listCoordinates(ListCoordinatesRequest request,
            StreamObserver<ListCoordinatesResponse> responseObserver) {
        log.info("IkeKnowledgeGraph listCoordinates request");
        List<ai.ica.tinkar.dto.SavedCoordinateResponse> list = coordinateStoreService.findAll();
        ListCoordinatesResponse response = ListCoordinatesResponse.newBuilder()
                .addAllCoordinates(list.stream().map(this::toSaveCoordinateResponse).toList())
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getSemanticsWithCoordinate(SemanticsWithCoordinateRequest request,
            StreamObserver<TinkarConceptSemanticsResponse> responseObserver) {
        String conceptId = extractConceptId(request.getConceptPublicId());
        String coordinateId = request.getCoordinateId();
        log.info("IkeKnowledgeGraph getSemanticsWithCoordinate conceptId={} coordinateId={}", conceptId, coordinateId);
        ai.ica.tinkar.dto.SavedCoordinateResponse coord = coordinateStoreService.findById(coordinateId)
                .orElseThrow(() -> Status.NOT_FOUND
                        .withDescription("No coordinate found with id: " + coordinateId)
                        .asRuntimeException());
        ViewCalculatorWithCache calc = CoordinateFactory.buildCalculator(coord.settings());
        responseObserver.onNext(tinkarService.getConceptSemanticsProto(conceptId, calc));
        responseObserver.onCompleted();
    }

    // ── Conversion helpers ────────────────────────────────────────────────────

    /**
     * Converts a proto {@link ai.ica.tinkar.proto.CoordinateOverride} to the DTO equivalent.
     * Used both by {@code buildCalculator} and {@code saveCoordinate}.
     */
    private ai.ica.tinkar.dto.CoordinateOverride protoToDto(ai.ica.tinkar.proto.CoordinateOverride proto) {
        if (proto == null) return null;
        // Pass enum name directly ("ACTIVE", "INACTIVE") — matches CoordinateFactory accepted values
        String allowedStates = proto.getAllowedStates() == AllowedStates.ACTIVE_AND_INACTIVE
                ? null : proto.getAllowedStates().name();
        Long positionTime = proto.getPositionTime() != 0 ? proto.getPositionTime() : null;
        String positionPathId = proto.getPositionPathId().isEmpty() ? null : proto.getPositionPathId();
        List<String> moduleIds = proto.getModuleIdsList().isEmpty() ? null : proto.getModuleIdsList();
        List<String> excludedModuleIds = proto.getExcludedModuleIdsList().isEmpty() ? null : proto.getExcludedModuleIdsList();
        List<String> modulePriorityIds = proto.getModulePriorityIdsList().isEmpty() ? null : proto.getModulePriorityIdsList();
        PremiseType premiseType = proto.getPremiseType() == ProtoPremiseType.STATED ? PremiseType.STATED : null;
        return new ai.ica.tinkar.dto.CoordinateOverride(
                allowedStates, positionTime, positionPathId, moduleIds, excludedModuleIds, modulePriorityIds, premiseType);
    }

    /**
     * Converts a DTO {@link ai.ica.tinkar.dto.CoordinateOverride} back to its proto equivalent.
     * Used when building {@link SaveCoordinateResponse}.
     */
    private ai.ica.tinkar.proto.CoordinateOverride dtoToProto(ai.ica.tinkar.dto.CoordinateOverride dto) {
        if (dto == null) return ai.ica.tinkar.proto.CoordinateOverride.getDefaultInstance();
        var builder = ai.ica.tinkar.proto.CoordinateOverride.newBuilder();
        if (dto.allowedStates() != null) {
            builder.setAllowedStates(switch (dto.allowedStates().toUpperCase()) {
                case "ACTIVE" -> AllowedStates.ACTIVE;
                case "INACTIVE" -> AllowedStates.INACTIVE;
                default -> AllowedStates.ACTIVE_AND_INACTIVE;
            });
        }
        if (dto.positionTime() != null) builder.setPositionTime(dto.positionTime());
        if (dto.positionPathId() != null) builder.setPositionPathId(dto.positionPathId());
        if (dto.moduleIds() != null) builder.addAllModuleIds(dto.moduleIds());
        if (dto.excludedModuleIds() != null) builder.addAllExcludedModuleIds(dto.excludedModuleIds());
        if (dto.modulePriorityIds() != null) builder.addAllModulePriorityIds(dto.modulePriorityIds());
        if (dto.premiseType() != null) {
            builder.setPremiseType(dto.premiseType() == PremiseType.STATED ? ProtoPremiseType.STATED : ProtoPremiseType.INFERRED);
        }
        return builder.build();
    }

    private SaveCoordinateResponse toSaveCoordinateResponse(ai.ica.tinkar.dto.SavedCoordinateResponse dto) {
        return SaveCoordinateResponse.newBuilder()
                .setId(dto.id() != null ? dto.id() : "")
                .setName(dto.name() != null ? dto.name() : "")
                .setSettings(dtoToProto(dto.settings()))
                .setCreatedAt(dto.createdAt() != null ? dto.createdAt() : "")
                .build();
    }

    private ViewCalculatorWithCache buildCalculator(ai.ica.tinkar.proto.CoordinateOverride protoOverride) {
        if (protoOverride == null || protoOverride.equals(ai.ica.tinkar.proto.CoordinateOverride.getDefaultInstance())) {
            return CoordinateFactory.defaultCalculator();
        }
        return CoordinateFactory.buildCalculator(protoToDto(protoOverride));
    }

    private String extractConceptId(PublicId publicId) {
        if (publicId == null || publicId.getUuidsList().isEmpty()) {
            return "";
        }
        return publicId.getUuids(0);
    }
}
