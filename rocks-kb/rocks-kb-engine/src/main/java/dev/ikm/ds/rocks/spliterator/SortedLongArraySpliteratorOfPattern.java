package dev.ikm.ds.rocks.spliterator;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Spliterator;
import java.util.function.LongConsumer;

/**
 * A primitive {@link java.util.Spliterator.OfLong} over a sorted {@code long[]} range.
 *
 * <p>Characteristics:</p>
 * <ul>
 *   <li>{@link java.util.Spliterator#ORDERED ORDERED} – elements are traversed in ascending index order</li>
 *   <li>{@link java.util.Spliterator#SORTED SORTED} – values are in ascending natural order
 *       (see {@link #getComparator()} which returns {@code null} per the contract)</li>
 *   <li>{@link java.util.Spliterator#SIZED SIZED} and {@link java.util.Spliterator#SUBSIZED SUBSIZED} – size is known and split sizes are exact</li>
 *   <li>{@link java.util.Spliterator#IMMUTABLE IMMUTABLE} – the backing array is not modified by this spliterator</li>
 * </ul>
 *
 * <p>Splitting policy:</p>
 * <ul>
 *   <li>{@link #trySplit()} performs a binary split of the current half-open range {@code [index, fence)}</li>
 *   <li>The newly created spliterator covers the lower half {@code [index, mid)} and
 *       the current instance advances to {@code [mid, fence)}</li>
 * </ul>
 *
 * <p>Traversal:</p>
 * <ul>
 *   <li>{@link #tryAdvance(java.util.function.LongConsumer)} emits the next element if available</li>
 *   <li>{@link #forEachRemaining(java.util.function.LongConsumer)} emits all remaining elements</li>
 * </ul>
 *
 * <p>Preconditions:</p>
 * <ul>
 *   <li>The referenced array segment {@code a[origin..fence)} must be globally nondecreasing (sorted ascending)</li>
 * </ul>
 *
 * <p>Per the {@link java.util.Spliterator#SORTED} contract, {@link #getComparator()} returns {@code null}
 * to indicate the natural {@code long} order.</p>
 *
 * @implNote This spliterator does not perform bounds checking beyond the constructor invariants and
 * assumes the provided indices are valid for {@code a}.
 */
public final class SortedLongArraySpliteratorOfPattern implements LongSpliteratorOfPattern {
    private final long[] a;
    private int index;        // inclusive
    private final int fence;  // exclusive
    private final boolean parallel;

    public static ImmutableList<LongSpliteratorOfPattern> of(long[] provided) {
        return of(provided, true);
    }

    public static ImmutableList<LongSpliteratorOfPattern> of(long[] provided, boolean parallel) {
        long[] copy = Arrays.copyOf(provided, provided.length);
        Arrays.parallelSort(copy);
        // Partition the sorted array into runs having the same upper 16-bit pattern
        MutableList<LongSpliteratorOfPattern> parts = Lists.mutable.empty();
        int n = copy.length;
        int start = 0;
        while (start < n) {
            int startPattern = (int)((copy[start] >>> 48) & 0xFFFFL);
            int end = start + 1;
            while (end < n) {
                int p = (int)((copy[end] >>> 48) & 0xFFFFL);
                if (p != startPattern) break;
                end++;
            }
            // create independent spliterator for [start, end)
            parts.add(new SortedLongArraySpliteratorOfPattern(copy, start, end, startPattern, parallel));
            start = end;
        }
        return parts.toImmutable();
    }
    /**
     * Creates a spliterator over the half-open range {@code [origin, fence)} of the given array.
     *
     * @param a the backing array (assumed to be sorted ascending in the covered range)
     * @param origin the inclusive lower bound (initial cursor position)
     * @param fence the exclusive upper bound (one past the last index)
     */
    private final int pattern;

    private SortedLongArraySpliteratorOfPattern(long[] a, int origin, int fence) {
        this(a, origin, fence, computePattern(a, origin, fence), true);
    }

    private SortedLongArraySpliteratorOfPattern(long[] a, int origin, int fence, int pattern, boolean parallel) {
        this.a = a;
        this.index = origin;
        this.fence = fence;
        this.pattern = pattern;
        this.parallel = parallel;
    }

    private static int computePattern(long[] a, int origin, int fence) {
        if (origin < fence) {
            return (int)((a[origin] >>> 48) & 0xFFFFL);
        }
        return -1; // empty
    }

    /**
     * Attempts to split this spliterator.
     *
     * <p>On success, returns a new spliterator covering the lower half of the
     * remaining range, i.e., {@code [index, mid)}. The current spliterator
     * advances to start at {@code mid} and continues to cover {@code [mid, fence)}.</p>
     *
     * @return a new spliterator covering {@code [index, mid)} if the range can be split; otherwise {@code null}
     * @implSpec This implementation performs a midpoint split using unsigned right shift.
     */
    @Override
    public OfLong trySplit() {
        if (!parallel) return null;
        int lo = index, hi = fence;
        int mid = (lo + hi) >>> 1;
        if (lo >= mid) return null;
        index = mid; // this keeps [mid, hi)
        return new SortedLongArraySpliteratorOfPattern(a, lo, mid, pattern, parallel); // split gets [lo, mid)
    }

    /**
     * If a remaining element exists, performs the given action on it and returns {@code true};
     * otherwise returns {@code false}.
     *
     * @param action the action to perform on the next element
     * @return {@code true} if an element was processed; {@code false} if no elements remain
     * @throws NullPointerException if the specified action is {@code null}
     * @implSpec This implementation reads the next element from {@code a[index]} and increments the cursor.
     */
    @Override
    public boolean tryAdvance(LongConsumer action) {
        int i = index;
        if (i >= fence) return false;
        action.accept(a[i]);
        index = i + 1;
        return true;
    }

    /**
     * Performs the given action for each remaining element in ascending index order
     * until all elements have been processed or the action throws an exception.
     *
     * @param action the action to perform on the remaining elements
     * @throws NullPointerException if the specified action is {@code null}
     * @implSpec This implementation iterates sequentially from the current cursor to {@code fence}.
     */
    @Override
    public void forEachRemaining(LongConsumer action) {
        for (int i = index, f = fence; i < f; i++) action.accept(a[i]);
        index = fence;
    }

    /**
     * Returns an estimate of the number of elements remaining to be traversed.
     *
     * @return the exact number of remaining elements
     * @implSpec Since the size is known, this is an exact count {@code (fence - index)}.
     */
    @Override
    public long estimateSize() {
        return (fence - index);
    }

    /**
     * Returns a set of characteristics of this spliterator and its elements.
     *
     * @return a representation of characteristics
     * @implSpec This implementation reports {@code ORDERED | SORTED | SIZED | SUBSIZED | IMMUTABLE}.
     */
    @Override
    public int characteristics() {
        return Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED
                | Spliterator.IMMUTABLE | Spliterator.SORTED;
    }

    /**
     * If this spliterator's source is {@link java.util.Spliterator#SORTED SORTED},
     * returns the associated comparator, or {@code null} if the elements are sorted
     * in natural order.
     *
     * @return {@code null}, indicating natural order for primitive {@code long} values
     * @implSpec Returning {@code null} complies with the {@code SORTED} contract for natural ordering.
     */
    @Override
    public Comparator<? super Long> getComparator() {
        return null;
    }

    // OfPatternLongSpliterator
    @Override
    public long peek() {
        int i = index;
        if (i >= fence) return Long.MIN_VALUE;
        return a[i];
    }

    @Override
    public int patternSequence() {
        return pattern;
    }
}