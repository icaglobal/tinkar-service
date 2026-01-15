package dev.ikm.ds.rocks;

import dev.ikm.ds.rocks.tasks.ImportProtobufTask;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.*;
import dev.ikm.tinkar.common.util.time.Stopwatch;
import dev.ikm.tinkar.entity.util.EntityCounter;
import dev.ikm.tinkar.entity.util.EntityProcessor;
import dev.ikm.tinkar.entity.util.EntityRealizer;
import dev.ikm.tinkar.terms.EntityBinding;
import dev.ikm.tinkar.terms.TinkarTerm;
import org.junit.jupiter.api.Disabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Formatter;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ObjIntConsumer;

@Disabled
class ImportProtobufTaskTest {
    private static final Logger LOG = LoggerFactory.getLogger(ImportProtobufTaskTest.class);
    File importFile = new File("/Users/kec/Solor/snomedct-international-20250101T120000Z+20250813-unreasoned-pb.zip");
    //File importFile = new File("/Users/kec/Solor/gudid-20250804-1.0.0-all-reasoned-pb.zip");
    //File importFile = new File("/Users/kec/Solor/SOLOR-GUDID-20250827-reasoned-pb.zip");
    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        LOG.info("Setting up RocksImportTaskTest");
//        ExecutorProviderController executorProviderController = new ExecutorProviderController();
//        executorProviderController.create();
        File defaultDataDirectory = new File("target/rocksdb/");
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, defaultDataDirectory);
        RocksProvider.NewController controller = new RocksProvider.NewController();
        controller.setDataServiceProperty(RocksProvider.NewController.NEW_FOLDER_PROPERTY, "RocksKb");
        controller.setDataUriOption(new DataUriOption("snomedct-international", importFile.toURI()));
        PrimitiveData.selectControllerByClass(RocksProvider.NewController.class);
        PrimitiveData.start();
        EntityKey key1 = RocksProvider.get().getEntityKey(EntityBinding.Stamp.pattern(), PublicIds.of(PrimitiveData.NONEXISTENT_STAMP_UUID));
        EntityKey key2 = RocksProvider.get().getEntityKey(EntityBinding.Concept.pattern(), TinkarTerm.AUTHOR_FOR_VERSION);
        EntityKey key3 = RocksProvider.get().getEntityKey(EntityBinding.Concept.pattern(), TinkarTerm.UNINITIALIZED_COMPONENT);
        EntityKey key4 = RocksProvider.get().getEntityKey(EntityBinding.Concept.pattern(), TinkarTerm.PRIMORDIAL_STATE);
        EntityKey key5 = RocksProvider.get().getEntityKey(EntityBinding.Concept.pattern(), TinkarTerm.ACTIVE_STATE);
        EntityKey key6 = RocksProvider.get().getEntityKey(EntityBinding.Concept.pattern(), TinkarTerm.INACTIVE_STATE);
        EntityKey key7 = RocksProvider.get().getEntityKey(EntityBinding.Concept.pattern(), TinkarTerm.WITHDRAWN_STATE);
        EntityKey key8 = RocksProvider.get().getEntityKey(EntityBinding.Concept.pattern(), TinkarTerm.CANCELED_STATE);
        LOG.info("Finished setting up RocksImportTaskTest");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        LOG.info("Closing RocksProvider");
        PrimitiveData.get().close();
        LOG.info("Closed RocksProvider");
    }

    @org.junit.jupiter.api.Test
    void compute() {
        try {
            HashSet<UUID> watchList = new HashSet<>();
            watchList.add(UUID.fromString("8bfba944-3965-3946-9bcb-1e80a5da63a2"));
            watchList.add(UUID.fromString("d6fad981-7df6-3388-94d8-238cc0465a79"));

            ImportProtobufTask importTask = new ImportProtobufTask(importFile, RocksProvider.get(), watchList);
            importTask.compute();

            Counter counter = new Counter();
            PrimitiveData.get().forEach(counter);
            LOG.info("Rocks Sequential count: " + counter.report());
            counter = new Counter();
            PrimitiveData.get().forEachParallel(counter);
            LOG.info("Rocks Parallel count:   " + counter.report());

            EntityCounter entityCounter = new EntityCounter();
            PrimitiveData.get().forEach(entityCounter);
            LOG.info("Rocks Entity count:     " + entityCounter.report());

            EntityProcessor processor = new EntityCounter();
            PrimitiveData.get().forEach(processor);
            LOG.info("Rocks Sequential count: \n" + processor.report() + "\n\n");
            processor = new EntityCounter();
            PrimitiveData.get().forEachParallel(processor);
            LOG.info("Rocks Parallel count: \n" + processor.report() + "\n\n");
            processor = new EntityRealizer();
            PrimitiveData.get().forEach(processor);
            LOG.info("Rocks Sequential realization: \n" + processor.report() + "\n\n");
            processor = new EntityRealizer();
            PrimitiveData.get().forEachParallel(processor);
            LOG.info("Rocks Parallel realization: \n" + processor.report() + "\n\n");
            processor = new EntityRealizer();
            PrimitiveData.get().forEach(processor);
            LOG.info("Rocks Sequential realization: \n" + processor.report() + "\n\n");
            processor = new EntityRealizer();
            PrimitiveData.get().forEachParallel(processor);
            LOG.info("Rocks Parallel realization: \n" + processor.report() + "\n\n");

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private static class Counter implements ObjIntConsumer<byte[]> {
        private final LongAdder count = new LongAdder();
        private final Stopwatch stopwatch = new Stopwatch();

        @Override
        public void accept(byte[] bytes, int value) {
            count.increment();
        }

        public String report() {
            this.stopwatch.end();
            StringBuilder sb = new StringBuilder();
            sb.append("").append(this.getClass().getSimpleName());
            new Formatter(sb).format(" Count: %,d", count.sum());
            sb.append(" in : ").append(stopwatch.durationString());
            sb.append(" (").append(stopwatch.averageDurationForElementString((int) count.sum()));
            sb.append(" each)");
            return sb.toString();
        }

        @Override
        public String toString() {
            return report();
        }
    }
}