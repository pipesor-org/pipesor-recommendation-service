-- Logical references already present in each immutable output, promoted to indexed columns for
-- the incident read (highest admitted version per incident and execution) and the worker.
ALTER TABLE diagnosis.diagnoses
    ADD COLUMN context_id uuid,
    ADD COLUMN execution_id uuid;

UPDATE diagnosis.diagnoses
SET context_id = (output->>'contextId')::uuid,
    execution_id = (output->>'executionId')::uuid;

ALTER TABLE diagnosis.diagnoses
    ALTER COLUMN context_id SET NOT NULL,
    ALTER COLUMN execution_id SET NOT NULL,
    ADD CONSTRAINT diagnoses_context_matches_output_ck CHECK (context_id = (output->>'contextId')::uuid),
    ADD CONSTRAINT diagnoses_execution_matches_output_ck CHECK (execution_id = (output->>'executionId')::uuid);

-- One admitted diagnosis per admitted context (its id is also derived from the context).
CREATE UNIQUE INDEX diagnoses_admitted_context_uq
    ON diagnosis.diagnoses (context_id) WHERE admission_fingerprint IS NOT NULL;
CREATE INDEX diagnoses_incident_execution_version_ix
    ON diagnosis.diagnoses (incident_id, execution_id, version DESC);
CREATE INDEX diagnoses_execution_version_ix
    ON diagnosis.diagnoses (execution_id, version DESC);
