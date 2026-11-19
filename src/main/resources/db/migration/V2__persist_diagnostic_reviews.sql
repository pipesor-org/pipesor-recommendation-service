CREATE TABLE diagnosis.diagnoses (
    id uuid PRIMARY KEY,
    incident_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    correlation_id text NOT NULL,
    authorization_scope jsonb NOT NULL,
    output jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (incident_id, version)
);

CREATE TABLE diagnosis.human_reviews (
    id uuid PRIMARY KEY,
    diagnostic_id uuid NOT NULL REFERENCES diagnosis.diagnoses(id),
    revision integer NOT NULL CHECK (revision > 0),
    reviewer_ref text NOT NULL,
    decision text NOT NULL CHECK (decision IN ('CONFIRMED', 'CORRECTED', 'REJECTED')),
    timestamp timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    correlation_id text NOT NULL,
    actual_cause text,
    comment text NOT NULL CHECK (btrim(comment) <> ''),
    corrected_recommendation text,
    UNIQUE (diagnostic_id, revision),
    CHECK ((decision = 'CORRECTED' AND actual_cause IS NOT NULL AND btrim(actual_cause) <> ''
                AND (corrected_recommendation IS NULL OR btrim(corrected_recommendation) <> ''))
           OR (decision <> 'CORRECTED' AND actual_cause IS NULL AND corrected_recommendation IS NULL))
);
