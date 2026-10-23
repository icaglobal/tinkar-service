package dev.ikm.tinkar.service.service.impl;

import dev.ikm.tinkar.common.id.LongIdSet;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.navigation.calculator.NavigationCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.foundation.ike.bindings.IkeConcept;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The navigation the primitive layer answers, against the IKE starter set in a real store.
 *
 * <p>{@link TinkarPrimitiveImpl#ancestorOf(PublicId)} returned the concept's descendants: it asks
 * the navigation calculator for {@code ancestorsOf(ConceptFacade)}, whose default called
 * {@code descendentsOf} ({@code IKE-Network/ike-issues#1316}). The test it replaces mocked the
 * navigation calculator, stubbing the very method that was wrong, so it could not see that.
 *
 * <p>The tests run the primitive layer, with no part of it replaced, against the reasoned IKE
 * starter set in the memory-only 64-bit store.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TinkarPrimitiveNavigationTest {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    /** A concept with both ancestors and descendants in the starter set's taxonomy. */
    private static final ConceptFacade PROBE = IkeConcept.MODEL_CONCEPT;

    private TinkarPrimitiveImpl primitive;
    private File dataRoot;

    @BeforeAll
    void startStore() throws IOException {
        assertThat(PB_STARTER_DATA)
                .as("the starter data, copied by maven-dependency-plugin in process-test-resources")
                .exists();
        dataRoot = Files.createTempDirectory("tinkar-primitive-navigation-test").toFile();
        CachingService.clearAll();
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, dataRoot);
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        long count = new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount();
        assertThat(count).as("entities loaded from the starter data").isPositive();

        // The store is running, so the primitive layer attaches to it instead of opening one.
        primitive = new TinkarPrimitiveImpl(dataRoot.getParent(), dataRoot.getName(), "Load Memory-Only 64-bit Store");
    }

    @AfterAll
    void stopStoreAndRemoveItsDirectory() throws IOException {
        PrimitiveData.stop();
        try (Stream<Path> paths = Files.walk(dataRoot.toPath())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ── ancestorOf ───────────────────────────────────────────────────────────

    @Test
    void ancestorOfReturnsTheConceptsAncestorsAndNoneOfItsDescendants() {
        NavigationCalculator navigation = Calculators.View.Default().navigationCalculator();
        long[] ancestors = sorted(navigation.ancestorsOf(PROBE.nid()));
        long[] descendants = sorted(navigation.descendentsOf(PROBE.nid()));
        long[] parents = navigation.parentsOf(PROBE.nid()).toArray();
        // The probe must tell the two answers apart, or a swap would pass unseen.
        assertThat(descendants).as("the probe's descendants").isNotEmpty();
        assertThat(ancestors).as("the probe's ancestors").contains(KernelConcept.ROOT_VERTEX.nid());

        long[] answered = nids(primitive.ancestorOf(PROBE.publicId()));

        assertThat(answered).as("ancestorOf reaches the root").contains(KernelConcept.ROOT_VERTEX.nid());
        assertThat(answered).as("ancestorOf includes the probe's parents").contains(parents);
        assertThat(answered).as("ancestorOf includes none of the probe's descendants").doesNotContain(descendants);
        assertThat(answered).as("ancestorOf answers what the calculator answers by nid").isEqualTo(ancestors);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static long[] sorted(LongIdSet nids) {
        long[] array = nids.toArray();
        Arrays.sort(array);
        return array;
    }

    private static long[] nids(List<PublicId> publicIds) {
        long[] array = publicIds.stream().mapToLong(PrimitiveData::nid).toArray();
        Arrays.sort(array);
        return array;
    }
}
