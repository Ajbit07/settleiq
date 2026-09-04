-- V7 makes the audit ledger's tamper evidence actually cover the CONTENT.
--
-- THE BUG THIS FIXES
--
-- audit_chain_break() only checked that each row's prev_hash equalled the
-- previous row's hash. It never recomputed a hash from the row's own columns.
-- So the links were verified but the contents were not, and anyone with write
-- access to the database could rewrite `payload`, `verdict` or `score` on a
-- committed row -- the money-bearing fields -- and the chain would still report
-- itself valid. Deletes, forged inserts and prev_hash edits were caught; the
-- one edit that actually changes what the ledger says was not.
--
-- Found by writing the test that performs the tampering rather than the test
-- that asserts the mechanism exists.
--
-- THE FIX
--
-- Verification now recomputes sha256(prev_hash || canonical(row)) for every row
-- and compares it with the stored hash. That requires the exact canonical
-- string to be reproducible from the stored row -- and it was not, because the
-- hash was computed over a Java-generated IST timestamp while the `ts` column
-- was populated by the database's own now(). Two different values; the one that
-- was hashed was thrown away.
--
-- canon_ts stores the exact timestamp string that went into the hash, so a row
-- can be re-canonicalised byte for byte years later.

-- Two values go into the hash that were never persisted:
--
--   canon_ts  -- the Java-generated IST timestamp, while the `ts` column was
--               filled by the database's own now(). Different values; the
--               hashed one was discarded.
--   canon_seq -- the ledger's PER-MERCHANT counter, while the `seq` column is a
--               GLOBAL bigserial. With two merchants interleaving, the stored
--               seq is not the number that was hashed.
--
-- Neither could be recovered from the row afterwards, so content verification
-- was impossible even in principle until both are stored.
ALTER TABLE audit_ledger ADD COLUMN canon_ts  TEXT;
ALTER TABLE audit_ledger ADD COLUMN canon_seq BIGINT;

COMMENT ON COLUMN audit_ledger.canon_ts IS
  'The exact timestamp string included in the hashed canonical form. NULL for '
  'rows written before V7, which therefore cannot be content-verified and are '
  'reported as unverifiable rather than as valid.';
COMMENT ON COLUMN audit_ledger.canon_seq IS
  'The per-merchant sequence number included in the hashed canonical form, '
  'which is not the same as the global bigserial in seq.';
