package ai.ica.tinkar.service;

import ai.ica.tinkar.dto.LanguageCoordinateDto;
import ai.ica.tinkar.dto.NavigationCoordinateDto;
import ai.ica.tinkar.dto.SavedLanguageCoordinateResponse;
import ai.ica.tinkar.dto.SavedNavigationCoordinateResponse;
import ai.ica.tinkar.dto.SavedStampCoordinateResponse;
import ai.ica.tinkar.dto.StampCoordinateDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.language.LanguageCoordinateRecord;
import dev.ikm.tinkar.coordinate.navigation.NavigationCoordinateRecord;
import dev.ikm.tinkar.coordinate.stamp.StampCoordinateRecord;
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
 *   <li>A {@code ConceptRecord} whose UUID is derived deterministically from the coordinate's
 *       content — identical settings always produce the same UUID (idempotent saves).</li>
 *   <li>A {@code SemanticRecord} attached to that concept using a <em>type-specific</em> pattern
 *       ({@link #STAMP_COORDINATE_PATTERN_UUID}, {@link #NAVIGATION_COORDINATE_PATTERN_UUID}, or
 *       {@link #LANGUAGE_COORDINATE_PATTERN_UUID}), with a single field: JSON-serialized coordinate
 *       settings.</li>
 * </ol>
 *
 * <p>Coordinates are stored in RocksDB and survive server restarts.
 * Listing uses {@code PrimitiveData.get().semanticNidsOfPattern(patternNid)}.
 */
@Component
@Slf4j
public class CoordinateStoreService {

    /** Deterministic UUID for the stamp-coordinate pattern concept. */
    static final UUID STAMP_COORDINATE_PATTERN_UUID =
            UUID.fromString("a3f7c21d-08b4-4e9a-bc63-1d2e5f780934");

    /** Deterministic UUID for the navigation-coordinate pattern concept. */
    static final UUID NAVIGATION_COORDINATE_PATTERN_UUID =
            UUID.fromString("7e4d91c0-3a52-4f1b-b8d6-9c0e27f41852");

    /** Deterministic UUID for the language-coordinate pattern concept. */
    static final UUID LANGUAGE_COORDINATE_PATTERN_UUID =
            UUID.fromString("b2e8f47a-5c31-4d0e-a97b-6f1234567890");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile int stampPatternNid = -1;
    private volatile int navigationPatternNid = -1;
    private volatile int languagePatternNid = -1;

    // ────────────────────────────────────────────────────────────────────────
    // Stamp coordinate

    /**
     * Save a StampCoordinate to the dataset. If an identical coordinate was previously saved
     * the existing record is returned unchanged (idempotent).
     */
    public SavedStampCoordinateResponse saveStamp(StampCoordinateDto dto) {
        StampCoordinateRecord record = CoordinateFactory.buildStampCoordinate(dto);
        UUID conceptUuid = record.getStampFilterUuid();
        int pNid = stampPatternNid();

        // Idempotency: return existing if already stored
        Optional<SavedStampCoordinateResponse> existing = findStampById(conceptUuid.toString());
        if (existing.isPresent()) {
            log.debug("Stamp coordinate {} already saved, returning existing", conceptUuid);
            return existing.get();
        }

        long now = System.currentTimeMillis();
        String settingsJson;
        try {
            settingsJson = objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize stamp coordinate settings", e);
        }

        Transaction tx = Transaction.make("Save stamp coordinate: " + conceptUuid);
        try {
            StampEntity<?> stamp = tx.getStamp(
                    State.ACTIVE, now,
                    TinkarTerm.USER.nid(),
                    TinkarTerm.SOLOR_OVERLAY_MODULE.nid(),
                    TinkarTerm.DEVELOPMENT_PATH.nid());

            ConceptRecord concept = ConceptRecord.build(conceptUuid, stamp.versions().get(0));
            EntityService.get().putEntity(concept);
            tx.addComponent(concept);

            int conceptNid = EntityService.get().nidForPublicId(PublicIds.of(conceptUuid));
            SemanticRecord semantic = SemanticRecord.build(
                    UUID.randomUUID(),
                    pNid,
                    conceptNid,
                    stamp.versions().get(0),
                    Lists.immutable.of(settingsJson));
            EntityService.get().putEntity(semantic);
            tx.addComponent(semantic);

            tx.commit();

            log.info("Saved stamp coordinate with id {}", conceptUuid);
            return new SavedStampCoordinateResponse(
                    conceptUuid.toString(), dto, Instant.ofEpochMilli(now).toString());

        } catch (Exception e) {
            tx.cancel();
            throw new RuntimeException("Failed to save stamp coordinate", e);
        }
    }

    /** List all saved StampCoordinates in the dataset. */
    public List<SavedStampCoordinateResponse> findAllStamp() {
        int pNid = stampPatternNid();
        int[] semanticNids = PrimitiveData.get().semanticNidsOfPattern(pNid);
        List<SavedStampCoordinateResponse> results = new ArrayList<>();
        for (int sNid : semanticNids) {
            deserializeStampSemantic(sNid).ifPresent(results::add);
        }
        return results;
    }

    /** Look up a saved StampCoordinate by its content-derived UUID string. */
    public Optional<SavedStampCoordinateResponse> findStampById(String coordinateId) {
        try {
            int pNid = stampPatternNid();
            PublicId pid = PublicIds.of(UUID.fromString(coordinateId));
            int cNid = EntityService.get().nidForPublicId(pid);
            int[] semanticNids = PrimitiveData.get().semanticNidsForComponentOfPattern(cNid, pNid);
            if (semanticNids.length == 0) return Optional.empty();
            return deserializeStampSemantic(semanticNids[0]);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid stamp coordinate ID format '{}': {}", coordinateId, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not find stamp coordinate '{}': {}", coordinateId, e.getMessage());
            return Optional.empty();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Navigation coordinate

    /**
     * Save a NavigationCoordinate to the dataset. Idempotent — identical settings return
     * the same UUID.
     */
    public SavedNavigationCoordinateResponse saveNavigation(NavigationCoordinateDto dto) {
        NavigationCoordinateRecord record = CoordinateFactory.buildNavigationCoordinate(dto);
        UUID conceptUuid = record.getNavigationCoordinateUuid();
        int pNid = navigationPatternNid();

        Optional<SavedNavigationCoordinateResponse> existing = findNavigationById(conceptUuid.toString());
        if (existing.isPresent()) {
            log.debug("Navigation coordinate {} already saved, returning existing", conceptUuid);
            return existing.get();
        }

        long now = System.currentTimeMillis();
        String settingsJson;
        try {
            settingsJson = objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize navigation coordinate settings", e);
        }

        Transaction tx = Transaction.make("Save navigation coordinate: " + conceptUuid);
        try {
            StampEntity<?> stamp = tx.getStamp(
                    State.ACTIVE, now,
                    TinkarTerm.USER.nid(),
                    TinkarTerm.SOLOR_OVERLAY_MODULE.nid(),
                    TinkarTerm.DEVELOPMENT_PATH.nid());

            ConceptRecord concept = ConceptRecord.build(conceptUuid, stamp.versions().get(0));
            EntityService.get().putEntity(concept);
            tx.addComponent(concept);

            int conceptNid = EntityService.get().nidForPublicId(PublicIds.of(conceptUuid));
            SemanticRecord semantic = SemanticRecord.build(
                    UUID.randomUUID(),
                    pNid,
                    conceptNid,
                    stamp.versions().get(0),
                    Lists.immutable.of(settingsJson));
            EntityService.get().putEntity(semantic);
            tx.addComponent(semantic);

            tx.commit();

            log.info("Saved navigation coordinate with id {}", conceptUuid);
            return new SavedNavigationCoordinateResponse(
                    conceptUuid.toString(), dto, Instant.ofEpochMilli(now).toString());

        } catch (Exception e) {
            tx.cancel();
            throw new RuntimeException("Failed to save navigation coordinate", e);
        }
    }

    /** List all saved NavigationCoordinates in the dataset. */
    public List<SavedNavigationCoordinateResponse> findAllNavigation() {
        int pNid = navigationPatternNid();
        int[] semanticNids = PrimitiveData.get().semanticNidsOfPattern(pNid);
        List<SavedNavigationCoordinateResponse> results = new ArrayList<>();
        for (int sNid : semanticNids) {
            deserializeNavigationSemantic(sNid).ifPresent(results::add);
        }
        return results;
    }

    /** Look up a saved NavigationCoordinate by its content-derived UUID string. */
    public Optional<SavedNavigationCoordinateResponse> findNavigationById(String coordinateId) {
        try {
            int pNid = navigationPatternNid();
            PublicId pid = PublicIds.of(UUID.fromString(coordinateId));
            int cNid = EntityService.get().nidForPublicId(pid);
            int[] semanticNids = PrimitiveData.get().semanticNidsForComponentOfPattern(cNid, pNid);
            if (semanticNids.length == 0) return Optional.empty();
            return deserializeNavigationSemantic(semanticNids[0]);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid navigation coordinate ID format '{}': {}", coordinateId, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not find navigation coordinate '{}': {}", coordinateId, e.getMessage());
            return Optional.empty();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Language coordinate

    /**
     * Save a LanguageCoordinate to the dataset. Idempotent — identical settings return
     * the same UUID.
     */
    public SavedLanguageCoordinateResponse saveLanguage(LanguageCoordinateDto dto) {
        LanguageCoordinateRecord record = CoordinateFactory.buildLanguageCoordinate(dto);
        UUID conceptUuid = record.getLanguageCoordinateUuid();
        int pNid = languagePatternNid();

        Optional<SavedLanguageCoordinateResponse> existing = findLanguageById(conceptUuid.toString());
        if (existing.isPresent()) {
            log.debug("Language coordinate {} already saved, returning existing", conceptUuid);
            return existing.get();
        }

        long now = System.currentTimeMillis();
        String settingsJson;
        try {
            settingsJson = objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize language coordinate settings", e);
        }

        Transaction tx = Transaction.make("Save language coordinate: " + conceptUuid);
        try {
            StampEntity<?> stamp = tx.getStamp(
                    State.ACTIVE, now,
                    TinkarTerm.USER.nid(),
                    TinkarTerm.SOLOR_OVERLAY_MODULE.nid(),
                    TinkarTerm.DEVELOPMENT_PATH.nid());

            ConceptRecord concept = ConceptRecord.build(conceptUuid, stamp.versions().get(0));
            EntityService.get().putEntity(concept);
            tx.addComponent(concept);

            int conceptNid = EntityService.get().nidForPublicId(PublicIds.of(conceptUuid));
            SemanticRecord semantic = SemanticRecord.build(
                    UUID.randomUUID(),
                    pNid,
                    conceptNid,
                    stamp.versions().get(0),
                    Lists.immutable.of(settingsJson));
            EntityService.get().putEntity(semantic);
            tx.addComponent(semantic);

            tx.commit();

            log.info("Saved language coordinate with id {}", conceptUuid);
            return new SavedLanguageCoordinateResponse(
                    conceptUuid.toString(), dto, Instant.ofEpochMilli(now).toString());

        } catch (Exception e) {
            tx.cancel();
            throw new RuntimeException("Failed to save language coordinate", e);
        }
    }

    /** List all saved LanguageCoordinates in the dataset. */
    public List<SavedLanguageCoordinateResponse> findAllLanguage() {
        int pNid = languagePatternNid();
        int[] semanticNids = PrimitiveData.get().semanticNidsOfPattern(pNid);
        List<SavedLanguageCoordinateResponse> results = new ArrayList<>();
        for (int sNid : semanticNids) {
            deserializeLanguageSemantic(sNid).ifPresent(results::add);
        }
        return results;
    }

    /** Look up a saved LanguageCoordinate by its content-derived UUID string. */
    public Optional<SavedLanguageCoordinateResponse> findLanguageById(String coordinateId) {
        try {
            int pNid = languagePatternNid();
            PublicId pid = PublicIds.of(UUID.fromString(coordinateId));
            int cNid = EntityService.get().nidForPublicId(pid);
            int[] semanticNids = PrimitiveData.get().semanticNidsForComponentOfPattern(cNid, pNid);
            if (semanticNids.length == 0) return Optional.empty();
            return deserializeLanguageSemantic(semanticNids[0]);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid language coordinate ID format '{}': {}", coordinateId, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not find language coordinate '{}': {}", coordinateId, e.getMessage());
            return Optional.empty();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Pattern NID helpers (lazy, double-checked locking)

    private int stampPatternNid() {
        if (stampPatternNid != -1) return stampPatternNid;
        synchronized (this) {
            if (stampPatternNid != -1) return stampPatternNid;
            stampPatternNid = resolveOrCreatePatternStub(STAMP_COORDINATE_PATTERN_UUID, "STAMP_COORDINATE_PATTERN");
        }
        return stampPatternNid;
    }

    private int navigationPatternNid() {
        if (navigationPatternNid != -1) return navigationPatternNid;
        synchronized (this) {
            if (navigationPatternNid != -1) return navigationPatternNid;
            navigationPatternNid = resolveOrCreatePatternStub(NAVIGATION_COORDINATE_PATTERN_UUID, "NAVIGATION_COORDINATE_PATTERN");
        }
        return navigationPatternNid;
    }

    private int languagePatternNid() {
        if (languagePatternNid != -1) return languagePatternNid;
        synchronized (this) {
            if (languagePatternNid != -1) return languagePatternNid;
            languagePatternNid = resolveOrCreatePatternStub(LANGUAGE_COORDINATE_PATTERN_UUID, "LANGUAGE_COORDINATE_PATTERN");
        }
        return languagePatternNid;
    }

    private int resolveOrCreatePatternStub(UUID patternUuid, String label) {
        PublicId pid = PublicIds.of(patternUuid);
        try {
            return EntityService.get().nidForPublicId(pid);
        } catch (IllegalStateException e) {
            // UUID not yet in DB — first use. Create the stub.
        }
        Transaction tx = Transaction.make("Init " + label);
        try {
            StampEntity<?> stamp = tx.getStamp(
                    State.ACTIVE, System.currentTimeMillis(),
                    TinkarTerm.USER.nid(),
                    TinkarTerm.SOLOR_OVERLAY_MODULE.nid(),
                    TinkarTerm.DEVELOPMENT_PATH.nid());
            ConceptRecord stub = ConceptRecord.build(patternUuid, stamp.versions().get(0));
            EntityService.get().putEntity(stub);
            tx.addComponent(stub);
            tx.commit();
        } catch (Exception e) {
            tx.cancel();
            throw new RuntimeException("Failed to initialize " + label, e);
        }
        int nid = EntityService.get().nidForPublicId(PublicIds.of(patternUuid));
        log.debug("Created {} stub (nid={})", label, nid);
        return nid;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Deserialization helpers

    private Optional<SavedStampCoordinateResponse> deserializeStampSemantic(int sNid) {
        try {
            Optional<Entity<?>> entityOpt = EntityService.get().packagePrivateGetEntity(sNid);
            if (entityOpt.isEmpty() || !(entityOpt.get() instanceof SemanticEntity<?> sem) || sem.versions().isEmpty()) {
                return Optional.empty();
            }
            SemanticEntityVersion ver = (SemanticEntityVersion) sem.versions().get(0);
            String json = (String) ver.fieldValues().get(0);
            StampCoordinateDto settings = objectMapper.readValue(json, StampCoordinateDto.class);

            Optional<Entity<?>> conceptEntityOpt = EntityService.get().packagePrivateGetEntity(sem.referencedComponentNid());
            if (conceptEntityOpt.isEmpty() || conceptEntityOpt.get().publicId() == null) return Optional.empty();
            String id = conceptEntityOpt.get().publicId().asUuidList().get(0).toString();

            long time = EntityService.get().getStampFast(ver.stampNid()).time();
            return Optional.of(new SavedStampCoordinateResponse(id, settings, Instant.ofEpochMilli(time).toString()));
        } catch (Exception e) {
            log.warn("Failed to deserialize stamp coordinate semantic nid={}: {}", sNid, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<SavedNavigationCoordinateResponse> deserializeNavigationSemantic(int sNid) {
        try {
            Optional<Entity<?>> entityOpt = EntityService.get().packagePrivateGetEntity(sNid);
            if (entityOpt.isEmpty() || !(entityOpt.get() instanceof SemanticEntity<?> sem) || sem.versions().isEmpty()) {
                return Optional.empty();
            }
            SemanticEntityVersion ver = (SemanticEntityVersion) sem.versions().get(0);
            String json = (String) ver.fieldValues().get(0);
            NavigationCoordinateDto settings = objectMapper.readValue(json, NavigationCoordinateDto.class);

            Optional<Entity<?>> conceptEntityOpt = EntityService.get().packagePrivateGetEntity(sem.referencedComponentNid());
            if (conceptEntityOpt.isEmpty() || conceptEntityOpt.get().publicId() == null) return Optional.empty();
            String id = conceptEntityOpt.get().publicId().asUuidList().get(0).toString();

            long time = EntityService.get().getStampFast(ver.stampNid()).time();
            return Optional.of(new SavedNavigationCoordinateResponse(id, settings, Instant.ofEpochMilli(time).toString()));
        } catch (Exception e) {
            log.warn("Failed to deserialize navigation coordinate semantic nid={}: {}", sNid, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<SavedLanguageCoordinateResponse> deserializeLanguageSemantic(int sNid) {
        try {
            Optional<Entity<?>> entityOpt = EntityService.get().packagePrivateGetEntity(sNid);
            if (entityOpt.isEmpty() || !(entityOpt.get() instanceof SemanticEntity<?> sem) || sem.versions().isEmpty()) {
                return Optional.empty();
            }
            SemanticEntityVersion ver = (SemanticEntityVersion) sem.versions().get(0);
            String json = (String) ver.fieldValues().get(0);
            LanguageCoordinateDto settings = objectMapper.readValue(json, LanguageCoordinateDto.class);

            Optional<Entity<?>> conceptEntityOpt = EntityService.get().packagePrivateGetEntity(sem.referencedComponentNid());
            if (conceptEntityOpt.isEmpty() || conceptEntityOpt.get().publicId() == null) return Optional.empty();
            String id = conceptEntityOpt.get().publicId().asUuidList().get(0).toString();

            long time = EntityService.get().getStampFast(ver.stampNid()).time();
            return Optional.of(new SavedLanguageCoordinateResponse(id, settings, Instant.ofEpochMilli(time).toString()));
        } catch (Exception e) {
            log.warn("Failed to deserialize language coordinate semantic nid={}: {}", sNid, e.getMessage());
            return Optional.empty();
        }
    }
}
