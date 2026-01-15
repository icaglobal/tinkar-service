package dev.ikm.ds.rocks.spliterator;

import dev.ikm.ds.rocks.EntityKey;
import dev.ikm.ds.rocks.NidCodec6;
import dev.ikm.ds.rocks.maps.SequenceMap;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.entity.Entity;

import java.util.Spliterator;

/**
 * Common extension for Spliterator.OfLong that is bound to a specific 16-bit pattern (upper bits of the long key)
 * and supports a non-destructive peek of the current value.
 */
public interface LongSpliteratorOfPattern extends Spliterator.OfLong {
    /**
     * Non-destructive peek at the current value that would be returned by tryAdvance/forEachRemaining next.
     * Returns Long.MIN_VALUE if there are no remaining elements.
     */
    long peek();
    /**
     * The 16-bit pattern (upper bits) that all values from this spliterator belong to.
     */
    int patternSequence();

    default EntityKey entityKeyForPattern() {
        return EntityKey.of(SequenceMap.PATTERN_PATTERN_SEQUENCE, patternSequence());
    }

    default PublicId publicIdForPattern() {
        return Entity.getFast(NidCodec6.nidForLongKey(entityKeyForPattern().longKey()));
    }
}
