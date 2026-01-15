package dev.ikm.ds.rocks.tasks;

import dev.ikm.ds.rocks.EntityKey;
import dev.ikm.ds.rocks.NidCodec6;
import dev.ikm.ds.rocks.RocksProvider;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.TrackingCallable;
import dev.ikm.tinkar.common.util.io.CountingInputStream;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.stamp.StampCoordinate;
import dev.ikm.tinkar.entity.*;
import dev.ikm.tinkar.entity.transform.TinkarSchemaToEntityTransformer;
import dev.ikm.tinkar.schema.PatternChronology;
import dev.ikm.tinkar.schema.SemanticChronology;
import dev.ikm.tinkar.schema.TinkarMsg;
import dev.ikm.tinkar.terms.EntityBinding;
import dev.ikm.tinkar.terms.EntityProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ImportProtobufTask extends TrackingCallable<dev.ikm.tinkar.common.service.EntityCountSummary> {

    protected static final Logger LOG = LoggerFactory.getLogger(ImportProtobufTask.class.getName());
    public static final int InputStreamBufferSize = 1024 * 1024;

    private final TinkarSchemaToEntityTransformer entityTransformer =
            TinkarSchemaToEntityTransformer.getInstance();
    private static final String MANIFEST_RELPATH = "META-INF/MANIFEST.MF";
    private final File importFile;
    private final RocksProvider provider;
    private final AtomicLong identifierCount = new AtomicLong();
    private final AtomicLong importCount = new AtomicLong();
    private final AtomicLong importConceptCount = new AtomicLong();
    private final AtomicLong importSemanticCount = new AtomicLong();
    private final AtomicLong importPatternCount = new AtomicLong();
    private final AtomicLong importStampCount = new AtomicLong();
    private final Set<UUID> watchList;

    public static final ScopedValue<TinkarMsg> SCOPED_TINKAR_MSG = ScopedValue.newInstance();
    public static final ScopedValue<Set<UUID>> SCOPED_WATCH_LIST = ScopedValue.newInstance();

    public ImportProtobufTask(File importFile, RocksProvider provider) {
        this(importFile, provider, Collections.emptySet());
    }
    public ImportProtobufTask(File importFile, RocksProvider provider, Set<UUID> watchList) {
        super(false, true);
        this.importFile = importFile;
        this.provider = provider;
        this.watchList = watchList;

        this.importFile.length();
        this.updateMessage("Loading entities from: " + importFile.getAbsolutePath());
        updateProgress(-1, 1);
        LOG.info("Loading entities from: {}", importFile.getAbsolutePath());
    }


    @Override
    public dev.ikm.tinkar.common.service.EntityCountSummary compute() throws Exception {
        initCounts();

        updateTitle("Import Protobuf Data from " + importFile.getName());

        updateMessage("Analyzing Import File...");

        // Analyze Manifest and update tracking callable
        long expectedImports = analyzeManifest();
        LOG.info(expectedImports + " Entities to process...");

        updateMessage("Extracting identifiers...");


        // Pass 1: generate identifiers for all entities
        EntityService.get().beginLoadPhase();
        CopyOnWriteArrayList<UUID> patternUuids = new CopyOnWriteArrayList<>();
        try (FileInputStream fileIn = new FileInputStream(importFile);
             BufferedInputStream buffIn = new BufferedInputStream(fileIn, InputStreamBufferSize);
             CountingInputStream countingIn = new CountingInputStream(buffIn);
             ZipInputStream zis = new ZipInputStream(countingIn)) {
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                updateProgress(countingIn.getBytesRead(), this.importFile.length() * 2);
                if (!zipEntry.getName().equals(MANIFEST_RELPATH)) {
                    Semaphore permits = new Semaphore(Runtime.getRuntime().availableProcessors() * 8);

                    try (StructuredTaskScope scope = StructuredTaskScope.open()) {
                        while (zis.available() > 0) {
                            // zis.available returns 1 until AFTER EOF has been reached 
                            TinkarMsg pbTinkarMsg = TinkarMsg.parseDelimitedFrom(zis);
                            permits.acquire();
                            scope.fork(() -> {
                                try {
                                    ScopedValue.where(SCOPED_TINKAR_MSG, pbTinkarMsg)
                                            .where(SCOPED_WATCH_LIST, watchList).call(() -> {
                                        if (pbTinkarMsg != null) {
                                            // Batch progress updates to prevent hanging the UI thread
                                            if (identifierCount.incrementAndGet() % 1000 == 0) {
                                                updateProgress(countingIn.getBytesRead(), this.importFile.length() * 2);
                                            }
                                            int nid = switch (pbTinkarMsg.getValueCase()) {
                                                case CONCEPT_CHRONOLOGY ->
                                                        makeNid(EntityBinding.Concept.pattern(), pbTinkarMsg.getConceptChronology().getPublicId());
                                                case SEMANTIC_CHRONOLOGY ->
                                                        makeNid(pbTinkarMsg.getSemanticChronology());
                                                case PATTERN_CHRONOLOGY ->
                                                        makeNid(EntityBinding.Pattern.pattern(), pbTinkarMsg.getPatternChronology().getPublicId());
                                                case STAMP_CHRONOLOGY ->
                                                        makeNid(EntityBinding.Stamp.pattern(), pbTinkarMsg.getStampChronology().getPublicId());
                                                case VALUE_NOT_SET ->
                                                        throw new IllegalStateException("Tinkar message value not set");
                                            };
                                            if (pbTinkarMsg.getValueCase().getNumber() == TinkarMsg.ValueCase.PATTERN_CHRONOLOGY.getNumber()) {
                                                PatternChronology patternChronology = pbTinkarMsg.getPatternChronology();
                                                String uuidStr = patternChronology.getPublicId().getUuidsList().get(0);
                                                UUID uuid = UUID.fromString(uuidStr);
                                                patternUuids.add(uuid);
                                            }
                                        }
                                        return null;

                                    });
                                } catch (Throwable t) {
                                    LOG.error("Unhandled exception in identifier subtask for msg: {}", pbTinkarMsg, t);
                                    throw t; // preserve failure semantics
                                } finally {
                                    permits.release();
                                }
                            });
                        }
                        scope.join();
                    }
                }
            }
            updateMessage("Imported identifiers...");
            LOG.info("Sequence report: {} ", this.provider.sequenceReport());
            LOG.info("Imported {} identifiers", String.format("%,d",identifierCount.get()));
        }

        // Pass 2: load entities into RocksDB
        EntityService.get().beginLoadPhase();
        try (FileInputStream fileIn = new FileInputStream(importFile);
             BufferedInputStream buffIn = new BufferedInputStream(fileIn, InputStreamBufferSize); // Increased buffer size
             CountingInputStream countingIn = new CountingInputStream(buffIn);
             ZipInputStream zis = new ZipInputStream(countingIn)) {
            // Consumer to be run for each transformed Entity
            Consumer<Entity<? extends EntityVersion>> entityConsumer = entity -> {
                EntityService.get().putEntityQuietly(entity);
                updateCounts(entity);
            };

            ZipEntry zipEntry;
            final AtomicInteger errorCount = new AtomicInteger();
            while ((zipEntry = zis.getNextEntry()) != null) {
                if (zipEntry.getName().equals(MANIFEST_RELPATH)) {
                    continue;
                }
                try (StructuredTaskScope scope = StructuredTaskScope.open()) {
                    Semaphore permits = new Semaphore(Runtime.getRuntime().availableProcessors() * 8);
                    while (zis.available() > 0) {
                        // zis.available returns 1 until AFTER EOF has been reached
                        // Protobuf parser reads the data UP TO EOF, so zis.available
                        // will return 1 when the only thing left is EOF
                        // pbTinkarMsg should be null for this last entry
                        TinkarMsg pbTinkarMsg = TinkarMsg.parseDelimitedFrom(zis);
                        if (pbTinkarMsg == null) {
                            continue;
                        }
                        permits.acquire();
                        scope.fork(() -> ScopedValue.where(SCOPED_TINKAR_MSG, pbTinkarMsg).call(() -> {
                            // TODO: Remove need for Stamp Consumer since Stamps are now consumed by Entity Consumer
                            try {
                                entityTransformer.transform(pbTinkarMsg, entityConsumer, (stampEntity) -> {
                                });
                                // Batch progress updates to prevent hanging the UI thread
                                if (importCount.incrementAndGet() % 1000 == 0) {
                                    updateProgress(this.importFile.length() + countingIn.getBytesRead(), this.importFile.length() * 2);
                                }
                            } catch (RuntimeException e) {
                                if (e instanceof IllegalStateException && e.getMessage().contains("No entity key found for UUIDs")) {
                                    LOG.error("{}. Error transforming Protobuf message: {} \n  {}", errorCount.getAndIncrement(), e.getMessage(), pbTinkarMsg);
                                }
                                if (e instanceof IllegalStateException && e.getMessage().contains("Entity byte[] not found")) {
                                    LOG.error("{}. Error transforming Protobuf message: {} \n  {}", errorCount.getAndIncrement(), e.getMessage(), pbTinkarMsg);
                                } else {
                                    LOG.error("{}. Error transforming Protobuf message: {}  \n  {}", errorCount.getAndIncrement(), e.getMessage(), pbTinkarMsg, e);
                                }
                            } finally {
                                permits.release();
                            }
                            return null;
                        }));
                    }
                    LOG.info("Starting scope.join");
                    scope.join();
                    LOG.info("Finished scope.join");

                }
            }
            LOG.info("Sequence report: {} ", this.provider.sequenceReport());
            StringBuilder stringBuilder = new StringBuilder();

            patternUuids.forEach(patternUuid -> {
                int nid = provider.nidForUuids(patternUuid);
                EntityKey entityKey = provider.getEntityKey(patternUuid).get();
                PatternEntity patternEntity = EntityService.get().getEntityFast(nid);
                StampCoordinate stampCoordinate = Coordinates.Stamp.DevelopmentLatest();
//                LanguageCalculatorWithCache languageCalculator = new LanguageCalculatorWithCache(stampCoordinate.toStampCoordinateRecord(),
//                        Lists.immutable.of(Coordinates.Language.UsEnglishFullyQualifiedName(), Coordinates.Language.AnyLanguageRegularName()));
//
//                String entityText = languageCalculator.getPreferredDescriptionTextOrNid(nid);
                String entityText = PrimitiveData.textWithNid(nid);
                stringBuilder.append("\n\nPattern: ").append(entityText).append(" EntityKey: ").append(entityKey);
                stringBuilder.append("\n nid=").append(nid).append(" (0x").append(String.format("%08X", nid)).append(")").append(" pattern sequence=").append(NidCodec6.decodePatternSequence(nid)).append(" element sequence=").append(NidCodec6.decodeElementSequence(nid));
                stringBuilder.append("\nPatternEntity: ").append(patternEntity);
            });

            LOG.info("PatternInfo:\n {}", stringBuilder);

            LOG.info("Imported {} entities", String.format("%,d", importCount.get()));
            LOG.info("Checking watchList: {} ", watchList);
            for (UUID uuid : watchList) {
                StringBuilder sb = new StringBuilder();
                Optional<EntityKey> entityKey = provider.getEntityKey(uuid);
                int nid = provider.nidForUuids(uuid);
                int patternSequence = provider.patternSequenceForNid(nid);
                long elementSequence = provider.elementSequenceForNid(nid);
                byte[] entityBytes = provider.getBytes(nid);
                sb.append("\n\nnid for ").append(uuid).append(" is: ").append(nid);
                sb.append("\npatternSequence for ").append(uuid).append(" is: ").append(patternSequence);
                sb.append("\nelementSequence for ").append(uuid).append(" is: ").append(elementSequence);
                sb.append("\nentityBytes for ").append(uuid).append(" is: ").append(Arrays.toString(entityBytes));
                sb.append("\nentityKey for ").append(uuid).append(" is: ").append(entityKey);
                sb.append("\nText with nid: ").append(PrimitiveData.textWithNid(nid));
                LOG.info(sb.toString());
            }
        } catch (IOException e) {
            updateTitle("Import Protobuf Data from " + importFile.getName() + " with error(s)");
            throw new RuntimeException(e);
        } finally {
            try {
                EntityService.get().endLoadPhase();
            } catch (Exception e) {
                LOG.error("Encountered exception {}", e.getMessage());
            }
            updateMessage("In " + durationString());
            updateProgress(1, 1);
        }
        if (importCount.get() != expectedImports) {
            LOG.warn("ERROR: Expected " + expectedImports + " imported Entities, but imported " + importCount.get());
        }
        return summarize();
    }


    private int makeNid(SemanticChronology semanticChronology) {
        return makeNid(EntityProxy.Pattern.make(getEntityPublicId(semanticChronology.getPatternForSemanticPublicId())),
                semanticChronology.getPublicId());
    }


    private int makeNid(EntityProxy.Pattern pattern, dev.ikm.tinkar.schema.PublicId pbPublicId) {
        PublicId publicId = getEntityPublicId(pbPublicId);
        int nid = makeNid(pattern, getEntityPublicId(pbPublicId));
        for (UUID uuid : publicId.asUuidArray()) {
            if (watchList.contains(uuid)) {
                LOG.info("Found watch: {} for: \n\n{}", uuid, SCOPED_TINKAR_MSG.get());
                LOG.info("nid for {} is: {}", uuid, nid);
                LOG.info("nid for {} in hex is: {}", uuid, Integer.toHexString(nid));
                LOG.info("EntityKey for {} is: {}", uuid, EntityKey.ofNid(nid));
            }
        }
        return nid;
    }

    private static PublicId getEntityPublicId(dev.ikm.tinkar.schema.PublicId pbPublicId) {
        return PublicIds.of(pbPublicId.getUuidsList().stream()
                .map(UUID::fromString)
                .toList());
    }

    private int makeNid(EntityProxy.Pattern pattern, PublicId entityId) {
        // Create the UUID -> Nid map here...
        if (SCOPED_WATCH_LIST.isBound()) {

        } else {
            LOG.info("Watch list not bound");
        }
        EntityKey entityKey = this.provider.getEntityKey(pattern.publicId(), entityId);
        return entityKey.nid();
    }


    private void updateCounts(Entity entity) {
        switch (entity) {
            case ConceptEntity ignored -> importConceptCount.incrementAndGet();
            case SemanticEntity ignored -> importSemanticCount.incrementAndGet();
            case PatternEntity ignored -> importPatternCount.incrementAndGet();
            case StampEntity ignored -> importStampCount.incrementAndGet();
            default -> throw new IllegalStateException("Unexpected value: " + entity);
        }
    }

    public dev.ikm.tinkar.common.service.EntityCountSummary summarize() {
        LOG.info("Imported: " + importCount.get() + " entities in: " + durationString());
        return new dev.ikm.tinkar.common.service.EntityCountSummary(
                importConceptCount.get(),
                importSemanticCount.get(),
                importPatternCount.get(),
                importStampCount.get()
        );
    }

    protected void initCounts() {
        importCount.set(0);
        importConceptCount.set(0);
        importSemanticCount.set(0);
        importPatternCount.set(0);
        importStampCount.set(0);
    }

    private long analyzeManifest() {
        long expectedImports = -1;
        Map<PublicId, String> manifestEntryData = new HashMap<>();

        // Read Manifest from Zip
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(importFile))) {
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                if (zipEntry.getName().equals(MANIFEST_RELPATH)) {
                    Manifest manifest = new Manifest(zis);
                    expectedImports = Long.parseLong(manifest.getMainAttributes().getValue("Total-Count"));
                    // Get Dependent Module / Author PublicIds and Descriptions
                    manifest.getEntries().keySet().forEach((publicIdKey) -> {
                        PublicId publicId = PublicIds.of(publicIdKey.split(","));
                        String description = manifest.getEntries().get(publicIdKey).getValue("Description");
                        manifestEntryData.put(publicId, description);
                    });
                }
                zis.closeEntry();
                LOG.info(zipEntry.getName() + " zip entry size: " + zipEntry.getSize());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return expectedImports;
    }
}
