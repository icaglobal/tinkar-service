package ai.ica.tinkar.service;

import ai.ica.tinkar.dto.CoordinateOverride;
import ai.ica.tinkar.dto.SavedCoordinateRequest;
import ai.ica.tinkar.dto.SavedCoordinateResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.ConceptRecord;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.State;
import dev.ikm.tinkar.terms.TinkarTerm;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.api.factory.Lists;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages saved coordinate configurations as first-class Tinkar entities in the loaded dataset.
 *
 * <p>Each saved coordinate consists of two entities written in one transaction:
 * <ol>
 *   <li>A {@code ConceptRecord} whose UUID is the <em>coordinate ID</em> returned to callers.</li>
 *   <li>A {@code SemanticRecord} attached to that concept using the
 *       {@code COORDINATE_SETTINGS_PATTERN} (a lazily-created stub concept), with two fields:
 *       <ul>
 *         <li>field[0]: coordinate name (String)</li>
 *         <li>field[1]: JSON-serialized {@link CoordinateOverride} (String)</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Coordinates are stored in RocksDB and survive server restarts.
 * Listing uses {@code PrimitiveData.get().semanticNidsOfPattern(patternNid)}.
 */
@Component
@Slf4j
public class CoordinateStoreService {

    /**
     * Deterministic UUID for the coordinate-settings pattern concept.
     * This concept is created as a minimal stub on first use.
     */
    static final UUID COORDINATE_SETTINGS_PATTERN_UUID =
            UUID.fromString("cafebabe-0001-4000-8000-000000000001");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Cached NID for COORDINATE_SETTINGS_PATTERN; -1 = not yet initialized. */
    private volatile int patternNid = -1;

    // ────────────────────────────────────────────────────────────────────────

    /**
     * Save a new coordinate configuration to the dataset.
     *
     * @param request coordinate name and settings
     * @return the persisted coordinate with its assigned UUID
     */
    public SavedCoordinateResponse save(SavedCoordinateRequest request) {
        int pNid = patternNid();
        UUID conceptUuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        String settingsJson;
        try {
            settingsJson = objectMapper.writeValueAsString(request.settings());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize coordinate settings", e);
        }

        Transaction tx = Transaction.make("Save coordinate: " + request.name());
        try {
            StampEntity<?> stamp = tx.getStamp(
                    State.ACTIVE, now,
                    TinkarTerm.USER.nid(),
                    TinkarTerm.SOLOR_OVERLAY_MODULE.nid(),
                    TinkarTerm.DEVELOPMENT_PATH.nid());

            // 1. Concept entity — the coordinate ID
            ConceptRecord concept = ConceptRecord.build(conceptUuid, stamp.versions().get(0));
            EntityService.get().putEntity(concept);
            tx.addComponent(concept);

            // 2. Semantic entity — name + JSON settings
            int conceptNid = EntityService.get().nidForPublicId(PublicIds.of(conceptUuid));
            SemanticRecord semantic = SemanticRecord.build(
                    UUID.randomUUID(),
                    pNid,
                    conceptNid,
                    stamp.versions().get(0),
                    Lists.immutable.of(request.name(), settingsJson));
            EntityService.get().putEntity(semantic);
            tx.addComponent(semantic);

            tx.commit();

            log.info("Saved coordinate '{}' with id {}", request.name(), conceptUuid);
            return new SavedCoordinateResponse(
                    conceptUuid.toString(),
                    request.name(),
                    request.settings(),
                    Instant.ofEpochMilli(now).toString());

        } catch (Exception e) {
            tx.cancel();
            throw new RuntimeException("Failed to save coordinate '" + request.name() + "'", e);
        }
    }

    /**
     * List all saved coordinates in the dataset.
     */
    public List<SavedCoordinateResponse> findAll() {
        int pNid = patternNid();
        int[] semanticNids = PrimitiveData.get().semanticNidsOfPattern(pNid);
        List<SavedCoordinateResponse> results = new ArrayList<>();
        for (int sNid : semanticNids) {
            deserializeCoordinateSemantic(sNid).ifPresent(results::add);
        }
        return results;
    }

    /**
     * Look up a saved coordinate by its concept UUID (the coordinate ID).
     *
     * @param coordinateId UUID string of the coordinate concept
     * @return the coordinate, or empty if not found or not a coordinate concept
     */
    public Optional<SavedCoordinateResponse> findById(String coordinateId) {
        try {
            int pNid = patternNid();
            PublicId pid = PublicIds.of(UUID.fromString(coordinateId));
            int cNid = EntityService.get().nidForPublicId(pid);
            int[] semanticNids = PrimitiveData.get().semanticNidsForComponentOfPattern(cNid, pNid);
            if (semanticNids.length == 0) return Optional.empty();
            return deserializeCoordinateSemantic(semanticNids[0]);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid coordinate ID format '{}': {}", coordinateId, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not find coordinate '{}': {}", coordinateId, e.getMessage());
            return Optional.empty();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Private helpers

    /**
     * Returns the NID for {@code COORDINATE_SETTINGS_PATTERN}, creating the concept stub
     * in the dataset on first call (lazy, double-checked locking).
     *
     * <p>{@code nidForPublicId} is a strict lookup that throws {@link IllegalStateException}
     * when the UUID is not yet in the database. On the very first call the UUID doesn't exist,
     * so we catch that exception, persist the stub (which registers the UUID→NID mapping via
     * {@code putEntity}), and then repeat the lookup.
     */
    private int patternNid() {
        if (patternNid != -1) return patternNid;
        synchronized (this) {
            if (patternNid != -1) return patternNid;
            PublicId pid = PublicIds.of(COORDINATE_SETTINGS_PATTERN_UUID);
            int nid;
            try {
                nid = EntityService.get().nidForPublicId(pid);
            } catch (IllegalStateException e) {
                // UUID not yet in DB — first use. Create the stub, which registers
                // the UUID→NID mapping via putEntity, then retry the lookup.
                nid = createCoordinatePatternStub();
            }
            patternNid = nid;
        }
        return patternNid;
    }

    /**
     * Creates the {@code COORDINATE_SETTINGS_PATTERN} concept stub in the database and
     * returns its NID. Called only once, on the very first coordinate operation.
     */
    private int createCoordinatePatternStub() {
        Transaction tx = Transaction.make("Init COORDINATE_SETTINGS_PATTERN");
        try {
            StampEntity<?> stamp = tx.getStamp(
                    State.ACTIVE, System.currentTimeMillis(),
                    TinkarTerm.USER.nid(),
                    TinkarTerm.SOLOR_OVERLAY_MODULE.nid(),
                    TinkarTerm.DEVELOPMENT_PATH.nid());
            ConceptRecord stub = ConceptRecord.build(
                    COORDINATE_SETTINGS_PATTERN_UUID, stamp.versions().get(0));
            EntityService.get().putEntity(stub);
            tx.addComponent(stub);
            tx.commit();
        } catch (Exception e) {
            tx.cancel();
            throw new RuntimeException("Failed to initialize COORDINATE_SETTINGS_PATTERN", e);
        }
        // putEntity registered the UUID→NID mapping; strict lookup now succeeds
        int nid = EntityService.get().nidForPublicId(PublicIds.of(COORDINATE_SETTINGS_PATTERN_UUID));
        log.debug("Created COORDINATE_SETTINGS_PATTERN stub (nid={})", nid);
        return nid;
    }

    private Optional<SavedCoordinateResponse> deserializeCoordinateSemantic(int sNid) {
        try {
            Optional<Entity<?>> entityOpt = EntityService.get().packagePrivateGetEntity(sNid);
            if (entityOpt.isEmpty() || !(entityOpt.get() instanceof SemanticEntity<?> sem) || sem.versions().isEmpty()) {
                return Optional.empty();
            }
            SemanticEntityVersion ver = (SemanticEntityVersion) sem.versions().get(0);

            String name = (String) ver.fieldValues().get(0);
            String json = (String) ver.fieldValues().get(1);
            CoordinateOverride settings = objectMapper.readValue(json, CoordinateOverride.class);

            Optional<Entity<?>> conceptEntityOpt = EntityService.get().packagePrivateGetEntity(sem.referencedComponentNid());
            if (conceptEntityOpt.isEmpty() || conceptEntityOpt.get().publicId() == null) return Optional.empty();
            String coordinateId = conceptEntityOpt.get().publicId().asUuidList().get(0).toString();

            long time = EntityService.get().getStampFast(ver.stampNid()).time();
            return Optional.of(new SavedCoordinateResponse(
                    coordinateId,
                    name,
                    settings,
                    Instant.ofEpochMilli(time).toString()));
        } catch (Exception e) {
            log.warn("Failed to deserialize coordinate semantic nid={}: {}", sNid, e.getMessage());
            return Optional.empty();
        }
    }
}
