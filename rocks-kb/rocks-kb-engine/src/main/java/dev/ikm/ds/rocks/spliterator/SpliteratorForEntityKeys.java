package dev.ikm.ds.rocks.spliterator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Spliterator;
import java.util.function.LongConsumer;

public class SpliteratorForEntityKeys implements Spliterator.OfLong {

    // The spliterator we are currently iterating
    private SpliteratorForLongKeyOfPattern current;

    // Remaining per-pattern spliterators to be iterated after 'current', sorted by ascending patternSequence
    private final Deque<SpliteratorForLongKeyOfPattern> remaining;

    public SpliteratorForEntityKeys(Collection<SpliteratorForLongKeyOfPattern> perPatternSpliterators) {
        List<SpliteratorForLongKeyOfPattern> list = new ArrayList<>();
        if (perPatternSpliterators != null) {
            for (SpliteratorForLongKeyOfPattern s : perPatternSpliterators) {
                if (s != null) list.add(s);
            }
        }
        // Sort by pattern sequence to achieve global natural key order:
        // upper 16 bits are patternSequence, then 48-bit element sequence.
        list.sort(Comparator.comparingInt(s -> s.patternSequence));

        this.remaining = new ArrayDeque<>();
        if (!list.isEmpty()) {
            this.current = list.get(0);
            for (int i = 1; i < list.size(); i++) {
                this.remaining.addLast(list.get(i));
            }
        }
    }

    @Override
    public OfLong trySplit() {
        // Prefer splitting by handing off a whole per-pattern spliterator.
        if (!remaining.isEmpty()) {
            return remaining.pollFirst();
        }
        // Only the current per-pattern spliterator remains; try to split it.
        if (current != null) {
            OfLong split = current.trySplit();
            if (split != null) {
                return split;
            }
        }
        return null;
    }

    @Override
    public boolean tryAdvance(LongConsumer action) {
        if (action == null) {
            throw new NullPointerException("action");
        }
        while (true) {
            if (current == null) {
                current = remaining.pollFirst();
                if (current == null) {
                    return false;
                }
            }
            if (current.tryAdvance(action)) {
                return true;
            }
            // Current exhausted; move to next
            current = null;
        }
    }

    @Override
    public void forEachRemaining(LongConsumer action) {
        if (action == null) {
            throw new NullPointerException("action");
        }
        if (current != null) {
            current.forEachRemaining(action);
            current = null;
        }
        SpliteratorForLongKeyOfPattern s;
        while ((s = remaining.pollFirst()) != null) {
            s.forEachRemaining(action);
        }
    }

    @Override
    public long estimateSize() {
        long total = 0L;
        if (current != null) {
            long size = current.estimateSize();
            if (size == Long.MAX_VALUE) return Long.MAX_VALUE;
            total = size;
        }
        for (SpliteratorForLongKeyOfPattern s : remaining) {
            long size = s.estimateSize();
            if (size == Long.MAX_VALUE) return Long.MAX_VALUE;
            long room = Long.MAX_VALUE - total; // saturating add
            total += (size > room) ? room : size;
        }
        return total;
    }

    @Override
    public int characteristics() {
        // Intersect characteristics across children.
        int ch = 0;
        boolean initialized = false;

        if (current != null) {
            ch = current.characteristics();
            initialized = true;
        }
        for (SpliteratorForLongKeyOfPattern s : remaining) {
            int sc = s.characteristics();
            ch = initialized ? (ch & sc) : sc;
            initialized = true;
        }

        if (!initialized) {
            // No sources; safe baseline
            return Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.ORDERED | Spliterator.IMMUTABLE | Spliterator.NONNULL | Spliterator.DISTINCT | Spliterator.SORTED;
        }

        // Because we iterate per-pattern in ascending order and each child is naturally sorted,
        // the concatenation is globally sorted by natural long order.
        ch |= Spliterator.SORTED;
        return ch;
    }

    @Override
    public Comparator<? super Long> getComparator() {
        // Natural order of long keys (ascending).
        return null;
    }


    @Override
    public String toString() {
        java.text.NumberFormat nf = java.text.NumberFormat.getIntegerInstance(java.util.Locale.getDefault());
        long totalEstimate = estimateSize();

        // Current spliterator (single line using its own toString)
        String currentStr = (current == null) ? "none" : current.toString();

        // Remaining spliterators (compact preview using each element's toString)
        java.util.ArrayList<String> rem = new java.util.ArrayList<>();
        for (SpliteratorForLongKeyOfPattern s : remaining) {
            rem.add(s.toString());
        }
        String remainingStr = rem.isEmpty()
                ? ""
                : ", " + String.join(", ", rem);

        return "SpliteratorForEntityKeys{"
                + "count: " + nf.format(totalEstimate)
                + "; " + currentStr
                + remainingStr
                + "}";
    }

}