package dev.ikm.ds.rocks;

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.*;
import dev.ikm.tinkar.common.util.time.Stopwatch;
import dev.ikm.tinkar.component.FieldDataType;
import dev.ikm.tinkar.entity.util.EntityCounter;
import dev.ikm.tinkar.entity.util.EntityProcessor;
import dev.ikm.tinkar.entity.util.EntityRealizer;
import dev.ikm.tinkar.terms.TinkarTerm;
import org.junit.jupiter.api.Disabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Formatter;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ObjIntConsumer;

@Disabled
class RocksOpenTaskTest {
    private static final Logger LOG = LoggerFactory.getLogger(RocksOpenTaskTest.class);

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        File defaultDataDirectory = new File("target/RocksKb/");
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, defaultDataDirectory);
        PrimitiveData.selectControllerByClass(RocksProvider.OpenController.class);
        PrimitiveData.start();
        PrimitiveData.stop();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        PrimitiveData.get().close();
    }

    @org.junit.jupiter.api.Test
    void compute() {
        try {
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