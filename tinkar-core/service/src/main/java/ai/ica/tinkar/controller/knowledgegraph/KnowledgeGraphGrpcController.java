package ai.ica.tinkar.controller.knowledgegraph;

import ai.ica.tinkar.dto.NavigationCoordinateDto;
import ai.ica.tinkar.dto.PremiseType;
import ai.ica.tinkar.dto.StampCoordinateDto;
import ai.ica.tinkar.proto.*;
import ai.ica.tinkar.service.CoordinateFactory;
import ai.ica.tinkar.service.CoordinateStoreService;
import ai.ica.tinkar.service.TinkarService;
import dev.ikm.tinkar.coordinate.navigation.NavigationCoordinateRecord;
import dev.ikm.tinkar.coordinate.stamp.StampCoordinateRecord;
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
    public void saveStampCoordinate(SaveStampCoordinateRequest request,
            StreamObserver<SavedStampCoordinateResponse> responseObserver) {
        log.info("IkeKnowledgeGraph saveStampCoordinate request");
        StampCoordinateDto dto = protoStampToDto(
                request.hasSettings() ? request.getSettings() : null);
        ai.ica.tinkar.dto.SavedStampCoordinateResponse saved = coordinateStoreService.saveStamp(dto);
        responseObserver.onNext(toProtoStampResponse(saved));
        responseObserver.onCompleted();
    }

    @Override
    public void listStampCoordinates(ListStampCoordinatesRequest request,
            StreamObserver<ListStampCoordinatesResponse> responseObserver) {
        log.info("IkeKnowledgeGraph listStampCoordinates request");
        List<ai.ica.tinkar.dto.SavedStampCoordinateResponse> list = coordinateStoreService.findAllStamp();
        ListStampCoordinatesResponse response = ListStampCoordinatesResponse.newBuilder()
                .addAllCoordinates(list.stream().map(this::toProtoStampResponse).toList())
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void saveNavigationCoordinate(SaveNavigationCoordinateRequest request,
            StreamObserver<SavedNavigationCoordinateResponse> responseObserver) {
        log.info("IkeKnowledgeGraph saveNavigationCoordinate request");
        NavigationCoordinateDto dto = protoNavToDto(
                request.hasSettings() ? request.getSettings() : null);
        ai.ica.tinkar.dto.SavedNavigationCoordinateResponse saved = coordinateStoreService.saveNavigation(dto);
        responseObserver.onNext(toProtoNavResponse(saved));
        responseObserver.onCompleted();
    }

    @Override
    public void listNavigationCoordinates(ListNavigationCoordinatesRequest request,
            StreamObserver<ListNavigationCoordinatesResponse> responseObserver) {
        log.info("IkeKnowledgeGraph listNavigationCoordinates request");
        List<ai.ica.tinkar.dto.SavedNavigationCoordinateResponse> list = coordinateStoreService.findAllNavigation();
        ListNavigationCoordinatesResponse response = ListNavigationCoordinatesResponse.newBuilder()
                .addAllCoordinates(list.stream().map(this::toProtoNavResponse).toList())
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getSemanticsWithCoordinate(SemanticsWithCoordinateRequest request,
            StreamObserver<TinkarConceptSemanticsResponse> responseObserver) {
        String conceptId = extractConceptId(request.getConceptPublicId());
        log.info("IkeKnowledgeGraph getSemanticsWithCoordinate conceptId={}", conceptId);

        String stampId = request.getStampCoordinateId().isEmpty() ? null : request.getStampCoordinateId();
        String navId = request.getNavigationCoordinateId().isEmpty() ? null : request.getNavigationCoordinateId();

        StampCoordinateRecord stampCoord = resolveStampCoordinate(stampId);
        NavigationCoordinateRecord navCoord = resolveNavigationCoordinate(navId);
        ViewCalculatorWithCache calc = CoordinateFactory.buildCalculator(stampCoord, navCoord);
        responseObserver.onNext(tinkarService.getConceptSemanticsProto(conceptId, calc));
        responseObserver.onCompleted();
    }

    // ── Conversion helpers ────────────────────────────────────────────────────

    private StampCoordinateDto protoStampToDto(StampCoordinateSettings proto) {
        if (proto == null) return null;
        String allowedStates = proto.getAllowedStates() == AllowedStates.ACTIVE_AND_INACTIVE
                ? null : proto.getAllowedStates().name();
        Long positionTime = proto.getPositionTime() != 0 ? proto.getPositionTime() : null;
        String positionPathId = proto.getPositionPathId().isEmpty() ? null : proto.getPositionPathId();
        List<String> moduleIds = proto.getModuleIdsList().isEmpty() ? null : proto.getModuleIdsList();
        List<String> excludedModuleIds = proto.getExcludedModuleIdsList().isEmpty() ? null : proto.getExcludedModuleIdsList();
        List<String> modulePriorityIds = proto.getModulePriorityIdsList().isEmpty() ? null : proto.getModulePriorityIdsList();
        return new StampCoordinateDto(allowedStates, positionTime, positionPathId, moduleIds, excludedModuleIds, modulePriorityIds);
    }

    private NavigationCoordinateDto protoNavToDto(NavigationCoordinateSettings proto) {
        if (proto == null) return null;
        PremiseType premiseType = proto.getPremiseType() == ProtoPremiseType.STATED ? PremiseType.STATED : null;
        return new NavigationCoordinateDto(premiseType);
    }

    private StampCoordinateSettings dtoStampToProto(StampCoordinateDto dto) {
        if (dto == null) return StampCoordinateSettings.getDefaultInstance();
        var builder = StampCoordinateSettings.newBuilder();
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
        return builder.build();
    }

    private NavigationCoordinateSettings dtoNavToProto(NavigationCoordinateDto dto) {
        if (dto == null) return NavigationCoordinateSettings.getDefaultInstance();
        var builder = NavigationCoordinateSettings.newBuilder();
        if (dto.premiseType() != null) {
            builder.setPremiseType(dto.premiseType() == PremiseType.STATED ? ProtoPremiseType.STATED : ProtoPremiseType.INFERRED);
        }
        return builder.build();
    }

    private SavedStampCoordinateResponse toProtoStampResponse(ai.ica.tinkar.dto.SavedStampCoordinateResponse dto) {
        return SavedStampCoordinateResponse.newBuilder()
                .setId(dto.id() != null ? dto.id() : "")
                .setSettings(dtoStampToProto(dto.settings()))
                .setCreatedAt(dto.createdAt() != null ? dto.createdAt() : "")
                .build();
    }

    private SavedNavigationCoordinateResponse toProtoNavResponse(ai.ica.tinkar.dto.SavedNavigationCoordinateResponse dto) {
        return SavedNavigationCoordinateResponse.newBuilder()
                .setId(dto.id() != null ? dto.id() : "")
                .setSettings(dtoNavToProto(dto.settings()))
                .setCreatedAt(dto.createdAt() != null ? dto.createdAt() : "")
                .build();
    }

    private StampCoordinateRecord resolveStampCoordinate(String stampCoordinateId) {
        if (stampCoordinateId == null) {
            return CoordinateFactory.buildStampCoordinate(null);
        }
        ai.ica.tinkar.dto.SavedStampCoordinateResponse saved = coordinateStoreService.findStampById(stampCoordinateId)
                .orElseThrow(() -> Status.NOT_FOUND
                        .withDescription("No stamp coordinate found with id: " + stampCoordinateId)
                        .asRuntimeException());
        return CoordinateFactory.buildStampCoordinate(saved.settings());
    }

    private NavigationCoordinateRecord resolveNavigationCoordinate(String navigationCoordinateId) {
        if (navigationCoordinateId == null) {
            return CoordinateFactory.buildNavigationCoordinate(null);
        }
        ai.ica.tinkar.dto.SavedNavigationCoordinateResponse saved = coordinateStoreService.findNavigationById(navigationCoordinateId)
                .orElseThrow(() -> Status.NOT_FOUND
                        .withDescription("No navigation coordinate found with id: " + navigationCoordinateId)
                        .asRuntimeException());
        return CoordinateFactory.buildNavigationCoordinate(saved.settings());
    }

    private ViewCalculatorWithCache buildCalculator(ai.ica.tinkar.proto.CoordinateOverride protoOverride) {
        if (protoOverride == null || protoOverride.equals(ai.ica.tinkar.proto.CoordinateOverride.getDefaultInstance())) {
            return CoordinateFactory.defaultCalculator();
        }
        String allowedStates = protoOverride.getAllowedStates() == AllowedStates.ACTIVE_AND_INACTIVE
                ? null : protoOverride.getAllowedStates().name();
        Long positionTime = protoOverride.getPositionTime() != 0 ? protoOverride.getPositionTime() : null;
        String positionPathId = protoOverride.getPositionPathId().isEmpty() ? null : protoOverride.getPositionPathId();
        List<String> moduleIds = protoOverride.getModuleIdsList().isEmpty() ? null : protoOverride.getModuleIdsList();
        List<String> excludedModuleIds = protoOverride.getExcludedModuleIdsList().isEmpty() ? null : protoOverride.getExcludedModuleIdsList();
        List<String> modulePriorityIds = protoOverride.getModulePriorityIdsList().isEmpty() ? null : protoOverride.getModulePriorityIdsList();
        PremiseType premiseType = protoOverride.getPremiseType() == ProtoPremiseType.STATED ? PremiseType.STATED : null;
        return CoordinateFactory.buildCalculator(new ai.ica.tinkar.dto.CoordinateOverride(
                allowedStates, positionTime, positionPathId, moduleIds, excludedModuleIds, modulePriorityIds, premiseType));
    }

    private String extractConceptId(PublicId publicId) {
        if (publicId == null || publicId.getUuidsList().isEmpty()) {
            return "";
        }
        return publicId.getUuids(0);
    }
}
