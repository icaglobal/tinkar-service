package dev.ikm.tinkar.service.service.impl;

import dev.ikm.tinkar.common.id.IntIdList;
import dev.ikm.tinkar.common.id.IntIdSet;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.navigation.calculator.NavigationCalculator;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.provider.search.Searcher;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TinkarPrimitiveImplTest {

    // ── constructor ──────────────────────────────────────────────────────────

    @Test
    void constructor_whenAlreadyRunning_skipsInitialization() {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class)) {
            pdMock.when(PrimitiveData::running).thenReturn(true);

            TinkarPrimitiveImpl impl = new TinkarPrimitiveImpl(".", ".", "test-controller");

            assertThat(impl).isNotNull();
        }
    }

    // ── getPublicId ──────────────────────────────────────────────────────────

    @Test
    void getPublicId_validUuid_returnsPublicIdWithUuid() {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class)) {
            TinkarPrimitiveImpl impl = createImpl(pdMock);
            String uuidStr = "550e8400-e29b-41d4-a716-446655440000";

            PublicId result = impl.getPublicId(uuidStr);
            ImmutableList<UUID> uuids = result.asUuidList();

            assertThat(uuids).hasSize(1);
            assertThat(uuids.get(0)).isEqualTo(UUID.fromString(uuidStr));
        }
    }

    @Test
    void getPublicId_invalidUuid_lazyParsingThrowsOnAccess() {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class)) {
            TinkarPrimitiveImpl impl = createImpl(pdMock);

            PublicId result = impl.getPublicId("not-valid-uuid");

            assertThatThrownBy(result::asUuidList)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── close ────────────────────────────────────────────────────────────────

    @Test
    void close_whenRunning_stopsPrimitiveData() {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class)) {
            pdMock.when(PrimitiveData::running).thenReturn(true);
            TinkarPrimitiveImpl impl = new TinkarPrimitiveImpl(".", ".", "test-controller");

            impl.close();

            pdMock.verify(PrimitiveData::stop);
        }
    }

    @Test
    void close_whenNotRunning_doesNotStopPrimitiveData() {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class)) {
            // running=true for the constructor, then false for close()
            pdMock.when(PrimitiveData::running).thenReturn(true).thenReturn(false);
            TinkarPrimitiveImpl impl = new TinkarPrimitiveImpl(".", ".", "test-controller");

            impl.close();

            pdMock.verify(PrimitiveData::stop, never());
        }
    }

    // ── search ───────────────────────────────────────────────────────────────

    @Test
    void search_emptyResults_returnsEmptyList() throws Exception {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class);
             MockedStatic<Calculators.View> calcMock = Mockito.mockStatic(Calculators.View.class)) {

            TinkarPrimitiveImpl impl = createImpl(pdMock);

            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            calcMock.when(Calculators.View::Default).thenReturn(mockCalc);
            when(mockCalc.search(any(), anyInt())).thenReturn(Lists.immutable.empty());

            List<PublicId> result = impl.search("test", 10);

            assertThat(result).isEmpty();
        }
    }

    @Test
    void search_exception_rethrowsException() throws Exception {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class);
             MockedStatic<Calculators.View> calcMock = Mockito.mockStatic(Calculators.View.class)) {

            TinkarPrimitiveImpl impl = createImpl(pdMock);

            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            calcMock.when(Calculators.View::Default).thenReturn(mockCalc);
            when(mockCalc.search(any(), anyInt())).thenThrow(new RuntimeException("search failed"));

            assertThatThrownBy(() -> impl.search("test", 10))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("search failed");
        }
    }

    // ── getLidrRecordSemanticsFromTestKit ────────────────────────────────────

    @Test
    void getLidrRecordSemanticsFromTestKit_delegatesToSearcher() {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class);
             MockedStatic<Searcher> searcherMock = Mockito.mockStatic(Searcher.class)) {

            TinkarPrimitiveImpl impl = createImpl(pdMock);
            PublicId pid = publicId("aaaaaaaa-0000-0000-0000-000000000001");
            searcherMock.when(() -> Searcher.getLidrRecordSemanticsFromTestKit(any()))
                    .thenReturn(List.of(publicId("aaaaaaaa-0000-0000-0000-000000000001")));

            List<PublicId> result = impl.getLidrRecordSemanticsFromTestKit(pid);

            assertThat(result).hasSize(1);
        }
    }

    // ── getResultConformancesFromLidrRecord ──────────────────────────────────

    @Test
    void getResultConformancesFromLidrRecord_delegatesToSearcher() {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class);
             MockedStatic<Searcher> searcherMock = Mockito.mockStatic(Searcher.class)) {

            TinkarPrimitiveImpl impl = createImpl(pdMock);
            PublicId pid = publicId("bbbbbbbb-0000-0000-0000-000000000001");
            searcherMock.when(() -> Searcher.getResultConformancesFromLidrRecord(any()))
                    .thenReturn(List.of(publicId("bbbbbbbb-0000-0000-0000-000000000002")));

            List<PublicId> result = impl.getResultConformancesFromLidrRecord(pid);

            assertThat(result).hasSize(1);
        }
    }

    // ── getAllowedResultsFromResultConformance ───────────────────────────────

    @Test
    void getAllowedResultsFromResultConformance_delegatesToSearcher() {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class);
             MockedStatic<Searcher> searcherMock = Mockito.mockStatic(Searcher.class)) {

            TinkarPrimitiveImpl impl = createImpl(pdMock);
            PublicId pid = publicId("cccccccc-0000-0000-0000-000000000001");
            searcherMock.when(() -> Searcher.getAllowedResultsFromResultConformance(any()))
                    .thenReturn(List.of(publicId("cccccccc-0000-0000-0000-000000000002")));

            List<PublicId> result = impl.getAllowedResultsFromResultConformance(pid);

            assertThat(result).hasSize(1);
        }
    }

    // ── descriptionsOf ───────────────────────────────────────────────────────

    @Test
    void descriptionsOf_delegatesToSearcher() {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class);
             MockedStatic<Searcher> searcherMock = Mockito.mockStatic(Searcher.class)) {

            TinkarPrimitiveImpl impl = createImpl(pdMock);
            searcherMock.when(() -> Searcher.descriptionsOf(any()))
                    .thenReturn(List.of("test description"));

            List<String> result = impl.descriptionsOf(List.of(publicId("aaaaaaaa-0000-0000-0000-000000000001")));

            assertThat(result).containsExactly("test description");
        }
    }

    // ── descendantsOf ────────────────────────────────────────────────────────

    @Test
    void descendantsOf_emptyResult_returnsEmptyList() {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class);
             MockedStatic<Calculators.View> calcMock = Mockito.mockStatic(Calculators.View.class);
             MockedStatic<Searcher> searcherMock = Mockito.mockStatic(Searcher.class)) {

            TinkarPrimitiveImpl impl = createImpl(pdMock);

            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            calcMock.when(Calculators.View::Default).thenReturn(mockCalc);

            NavigationCalculator mockNavCalc = Mockito.mock(NavigationCalculator.class);
            when(mockCalc.navigationCalculator()).thenReturn(mockNavCalc);

            IntIdSet mockIntIdSet = Mockito.mock(IntIdSet.class);
            when(mockNavCalc.descendentsOf(any())).thenReturn(mockIntIdSet);

            searcherMock.when(() -> Searcher.descriptionsOf(any())).thenReturn(List.of("desc"));

            List<PublicId> result = impl.descendantsOf(publicId("aaaaaaaa-0000-0000-0000-000000000001"));

            assertThat(result).isEmpty();
        }
    }

    // ── parentsOf ────────────────────────────────────────────────────────────

    @Test
    void parentsOf_emptyResult_returnsEmptyList() {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class);
             MockedStatic<Calculators.View> calcMock = Mockito.mockStatic(Calculators.View.class);
             MockedStatic<Searcher> searcherMock = Mockito.mockStatic(Searcher.class)) {

            TinkarPrimitiveImpl impl = createImpl(pdMock);

            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            calcMock.when(Calculators.View::Default).thenReturn(mockCalc);

            NavigationCalculator mockNavCalc = Mockito.mock(NavigationCalculator.class);
            when(mockCalc.navigationCalculator()).thenReturn(mockNavCalc);

            IntIdList mockIntIdList = Mockito.mock(IntIdList.class);
            when(mockNavCalc.parentsOf(any())).thenReturn(mockIntIdList);

            searcherMock.when(() -> Searcher.descriptionsOf(any())).thenReturn(List.of("desc"));

            List<PublicId> result = impl.parentsOf(publicId("bbbbbbbb-0000-0000-0000-000000000001"));

            assertThat(result).isEmpty();
        }
    }

    // ── ancestorOf ───────────────────────────────────────────────────────────

    @Test
    void ancestorOf_emptyResult_returnsEmptyList() {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class);
             MockedStatic<Calculators.View> calcMock = Mockito.mockStatic(Calculators.View.class);
             MockedStatic<Searcher> searcherMock = Mockito.mockStatic(Searcher.class)) {

            TinkarPrimitiveImpl impl = createImpl(pdMock);

            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            calcMock.when(Calculators.View::Default).thenReturn(mockCalc);

            NavigationCalculator mockNavCalc = Mockito.mock(NavigationCalculator.class);
            when(mockCalc.navigationCalculator()).thenReturn(mockNavCalc);

            IntIdSet mockIntIdSet = Mockito.mock(IntIdSet.class);
            when(mockNavCalc.ancestorsOf(any())).thenReturn(mockIntIdSet);

            searcherMock.when(() -> Searcher.descriptionsOf(any())).thenReturn(List.of("desc"));

            List<PublicId> result = impl.ancestorOf(publicId("cccccccc-0000-0000-0000-000000000001"));

            assertThat(result).isEmpty();
        }
    }

    // ── childrenOf ───────────────────────────────────────────────────────────

    @Test
    void childrenOf_emptyResult_returnsEmptyList() {
        try (MockedStatic<PrimitiveData> pdMock = Mockito.mockStatic(PrimitiveData.class);
             MockedStatic<Calculators.View> calcMock = Mockito.mockStatic(Calculators.View.class);
             MockedStatic<Searcher> searcherMock = Mockito.mockStatic(Searcher.class)) {

            TinkarPrimitiveImpl impl = createImpl(pdMock);

            ViewCalculatorWithCache mockCalc = Mockito.mock(ViewCalculatorWithCache.class);
            calcMock.when(Calculators.View::Default).thenReturn(mockCalc);

            NavigationCalculator mockNavCalc = Mockito.mock(NavigationCalculator.class);
            when(mockCalc.navigationCalculator()).thenReturn(mockNavCalc);

            IntIdList mockIntIdList = Mockito.mock(IntIdList.class);
            when(mockNavCalc.childrenOf(any())).thenReturn(mockIntIdList);

            searcherMock.when(() -> Searcher.descriptionsOf(any())).thenReturn(List.of("desc"));

            List<PublicId> result = impl.childrenOf(publicId("dddddddd-0000-0000-0000-000000000001"));

            assertThat(result).isEmpty();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static TinkarPrimitiveImpl createImpl(MockedStatic<PrimitiveData> pdMock) {
        pdMock.when(PrimitiveData::running).thenReturn(true);
        return new TinkarPrimitiveImpl(".", ".", "test-controller");
    }

    private static PublicId publicId(String uuidStr) {
        UUID uuid = UUID.fromString(uuidStr);
        return new PublicId() {
            @Override
            public int uuidCount() { return 1; }

            @Override
            public void forEach(LongConsumer c) {
                c.accept(uuid.getMostSignificantBits());
                c.accept(uuid.getLeastSignificantBits());
            }

            @Override
            public ImmutableList<UUID> asUuidList() {
                return Lists.immutable.with(uuid);
            }
        };
    }
}
