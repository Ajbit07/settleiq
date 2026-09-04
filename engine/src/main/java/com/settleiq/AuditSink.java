package com.settleiq;

import java.util.Map;

/**
 * Where audit entries go.
 *
 * The engine does not care whether that is a file or a Postgres table, but it
 * does care about two behaviours, and any implementation must provide both:
 *
 *   - {@link #seen(String)} must be authoritative about idempotency. If it
 *     returns true the engine writes nothing and reports the posting as
 *     suppressed. A sink that guesses here will double-post on a retry.
 *
 *   - {@link #append} must be atomic with respect to the idempotency key. In
 *     Postgres that is a UNIQUE constraint doing the work, not a prior SELECT;
 *     two workers racing on the same key must produce exactly one row.
 */
public interface AuditSink {

    boolean seen(String idempotencyKey);

    /** @return true if a row was written, false if suppressed as a duplicate. */
    boolean append(String actor, String action, String entityType, String entityId,
                   String idempotencyKey, String modelVersion, double score,
                   String verdict, Map<String, String> payload);

    /** Hash of the most recent row, or 64 zeroes for an empty chain. */
    String head();

    /** Count of postings suppressed as duplicates during this run. */
    int suppressedDuplicates();

    /** Rows written by this instance during this run. */
    int appendedCount();

    /**
     * The verdict this idempotency key was ORIGINALLY posted under, or null if
     * it has never been posted.
     *
     * Needed because "suppressed as a duplicate" describes what the ledger did,
     * not what was decided about the money. A re-run that reports every
     * exception as SUPPRESSED_DUPLICATE tells a controller nothing: the item is
     * still escalated and still needs a human, it just was not written twice.
     */
    default String priorVerdict(String idempotencyKey) { return null; }
}
