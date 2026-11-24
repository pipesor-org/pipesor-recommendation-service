-- ADR-027: transactional outbox feeding Pipeline's incident diagnosis summary. Admitting a diagnosis
-- and appending a review upsert the (incident, execution) row in the same transaction, bumping
-- `generation`; the publisher deletes a row only after Pipeline acknowledged the generation it read.
-- Workers claim due rows with FOR UPDATE SKIP LOCKED and a time-bounded lease. incident_id and
-- execution_id are logical references (no foreign keys).
CREATE TABLE diagnosis.incident_summary_outbox (
    incident_id uuid NOT NULL,
    execution_id uuid NOT NULL,
    generation bigint NOT NULL DEFAULT 1 CHECK (generation > 0),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_until timestamptz,
    last_failure text CHECK (last_failure IS NULL OR last_failure ~ '^[a-z][a-z0-9_]{0,63}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (incident_id, execution_id)
);

CREATE INDEX incident_summary_outbox_due_ix ON diagnosis.incident_summary_outbox (next_attempt_at);

-- Backfill: every incident and execution with an admitted diagnosis is projected once.
INSERT INTO diagnosis.incident_summary_outbox (incident_id, execution_id)
SELECT DISTINCT incident_id, execution_id
FROM diagnosis.diagnoses
WHERE admission_fingerprint IS NOT NULL;
