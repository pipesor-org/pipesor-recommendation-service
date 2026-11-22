-- Durable work queue for automatic diagnosis of admitted correlated contexts. One row per
-- receipt; workers claim rows with FOR UPDATE SKIP LOCKED and hold a time-bounded lease, so a
-- crashed worker's job becomes claimable again. Upstream identifiers remain logical references.
CREATE TABLE diagnosis.diagnosis_jobs (
    context_id uuid PRIMARY KEY REFERENCES diagnosis.correlated_context_receipts (context_id),
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'DONE', 'FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_until timestamptz,
    last_failure text CHECK (last_failure IS NULL OR last_failure ~ '^[a-z][a-z0-9_]{0,63}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (state = 'PENDING' OR lease_until IS NULL)
);

CREATE INDEX diagnosis_jobs_due_ix ON diagnosis.diagnosis_jobs (next_attempt_at) WHERE state = 'PENDING';

-- Receipts accepted before this version and still without an admitted diagnosis.
INSERT INTO diagnosis.diagnosis_jobs (context_id)
SELECT r.context_id
FROM diagnosis.correlated_context_receipts r
WHERE NOT EXISTS (
    SELECT 1 FROM diagnosis.diagnoses d
    WHERE d.context_id = r.context_id AND d.admission_fingerprint IS NOT NULL);
