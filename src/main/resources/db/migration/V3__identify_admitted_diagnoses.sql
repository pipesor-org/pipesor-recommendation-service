ALTER TABLE diagnosis.diagnoses
    ADD COLUMN admission_fingerprint text
    CHECK (admission_fingerprint IS NULL OR admission_fingerprint ~ '^[0-9a-f]{64}$');
