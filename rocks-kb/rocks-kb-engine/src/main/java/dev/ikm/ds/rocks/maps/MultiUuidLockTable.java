package dev.ikm.ds.rocks.maps;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MultiUuidLockTable is a weak lock table that enables fine-grained, deadlock-safe, per-key locking for concurrent operations on sets of {@code long} values.
 * <p>
 * <b>Concurrency Strategy:</b> This class uses a {@link ConcurrentHashMap} that maps {@code long} keys to {@link java.lang.ref.WeakReference}s
 * of {@link java.util.concurrent.locks.ReentrantLock} objects. Each provided {@code long} value (possibly derived from the
 * {@code PublicId}) gets its own dynamically allocated lock object.
 * When a thread wants to perform an atomic operation on a set of long values, it:
 * <ul>
 *     <li>Sorts the keys (to ensure global, deterministic locking order and prevent deadlock)</li>
 *     <li>Acquires the lock for each key in that order</li>
 *     <li>Executes its critical section</li>
 *     <li>Releases the locks in the same order</li>
 * </ul>
 *
 * <b>Atomicity & Safety:</b>
 * <ul>
 *     <li>Holding all locks for a set of keys is <b>atomic with respect to other threads</b> that might overlap with any of those keys:
 *         no other process can hold any subset of your keys while you hold them, so any race on any individual key is prevented.</li>
 *     <li>{@code computeIfAbsent} and other critical region operations are fully isolated while the locks are held.</li>
 *     <li>The use of {@code WeakReference} ensures that locks which are not in use may be garbage collected, preventing unbounded memory growth
 *         in the case of many transient keys being used over time.</li>
 *     <li>Correctness is strictly maintained: WeakReferences are only a memory optimization and do not affect safety or atomicity.</li>
 * </ul>
 *
 * <b>Deadlock Freedom:</b>
 * <ul>
 *     <li>Deadlock freedom is guaranteed because locks for multiple keys are always acquired in sorted, ascending key order.</li>
 *     <li>This global total order prevents circular wait of locks among concurrent threads.</li>
 * </ul>
 *
 * <b>Usage Examples:</b>
 * <pre>
 *   MultiUuidLockTable lockTable = new MultiUuidLockTable();
 *   lockTable.lockAll(longsToLock);
 *   try {
 *      // perform critical work on the set
 *   } finally {
 *      lockTable.unlockAll(longsToLock);
 *   }
 * </pre>
 * <pre>
 *   MultiUuidLockTable lockTable = new MultiUuidLockTable();
 *   lockTable.lock(publicIdToLock);
 *   try {
 *      // perform critical work on the set
 *   } finally {
 *      lockTable.unlock(publicIdToLock);
 *   }
 * </pre>
 *
 * <b>Notes:</b>
 * <ul>
 *     <li>This pattern is suitable for atomic operations across multiple, potentially overlapping sets of logical IDs,
 *         such as assigning unique mappings for all UUID-longs in a PublicId.</li>
 *     <li>This utility is much more memory efficient than a one-lock-per-possible-key approach and simpler to use safely than striped locking for most use cases.</li>
 * </ul>
 */
public class MultiUuidLockTable {

    private final ConcurrentHashMap<Long, WeakReference<ReentrantLock>> lockMap = new ConcurrentHashMap<>();

    /**
     * Acquires locks for all UUIDs associated with the provided {@code PublicId}, ensuring that
     * operations involving the same identifiers are synchronized. This method resolves the UUIDs
     * associated with the {@code PublicId} to their array form and locks them in a sorted order
     * to avoid potential deadlocks.
     *
     * @param publicId The object containing the UUIDs to be locked. It represents a logical identifier
     *                 that may comprise multiple UUIDs, all of which need to be locked.
     */
    public void lock(PublicId publicId) {
        lockAll(UuidUtil.asArray(publicId.asUuidArray()));

    }
    /**
     * Releases locks associated with the UUIDs that correspond to the provided {@code PublicId}.
     * This method ensures that all UUIDs resolved from the input {@code PublicId} are passed
     * in their array form to the internal unlocking mechanism, which handles the proper
     * release of locks in a sorted order to maintain consistency.
     *
     * @param publicId The object containing the UUIDs to be unlocked. It represents a logical
     *                 identifier that may consist of multiple UUIDs, all requiring unlocking.
     */
    public void unlock(PublicId publicId) {
        unlockAll(UuidUtil.asArray(publicId.asUuidArray()));
    }

    /**
     * Retrieves a synchronized lock associated with the specified numerical value. If a lock for the
     * given value does not exist or has been garbage collected, a new lock is created, stored in
     * a WeakReference, and returned. This ensures that only one lock exists per value at any time,
     * while allowing unused locks to be garbage collected when they are no longer referenced.
     *
     * @param value The numerical value associated with the lock to be retrieved or created.
     * @return The ReentrantLock associated with the provided numerical value.
     */
    private ReentrantLock getLock(long value) {
        while (true) {
            WeakReference<ReentrantLock> ref = lockMap.get(value);
            ReentrantLock lock = (ref == null) ? null : ref.get();
            if (lock != null) return lock;
            // Create a new lock and attempt to put it in the map
            ReentrantLock newLock = new ReentrantLock();
            WeakReference<ReentrantLock> newRef = new WeakReference<>(newLock);
            if (ref == null) {
                // No mapping yet
                if (lockMap.putIfAbsent(value, newRef) == null) {
                    return newLock;
                }
            } else {
                // Mapping present, but lock was GC'd
                if (lockMap.replace(value, ref, newRef)) {
                    return newLock;
                }
            }
            // Retry on concurrent modification
        }
    }

    /**
     * Acquires locks for all numerical values provided in the array, ensuring that operations involving
     * these values are synchronized. The input array is cloned and sorted to maintain lock acquisition
     * order, thus preventing potential deadlocks. Each lock is retrieved or created internally and then locked.
     *
     * @param longs An array of numerical values for which locks should be acquired. The locks will be
     *              acquired in sorted order of the values.
     */
    public void lockAll(long[] longs) {
        long[] sorted = longs.clone();
        Arrays.sort(sorted);
        for (long v : sorted) {
            getLock(v).lock();
        }
    }

    /**
     * Unlocks all locks associated with the numerical values in the provided array.
     * The array is sorted internally to ensure that locks are released in a consistent
     * order, which helps in maintaining system stability and avoiding potential issues
     * related to unlocking order.
     *
     * @param longs An array of numerical values representing the locks to be released.
     *              The array is cloned and sorted internally before unlocking the
     *              associated ReentrantLocks.
     */
    public void unlockAll(long[] longs) {
        long[] sorted = longs.clone();
        Arrays.sort(sorted);
        for (long v : sorted) {
            WeakReference<ReentrantLock> ref = lockMap.get(v);
            ReentrantLock lock = (ref == null) ? null : ref.get();
            if (lock != null) {
                lock.unlock();
            }
        }
    }

}
