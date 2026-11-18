-- DD-024/model 1.7.0. Own receipt only; upstream references remain logical.
CREATE SCHEMA IF NOT EXISTS diagnosis;

CREATE TABLE "diagnosis"."correlated_context_receipts" (
  "operation_id" uuid PRIMARY KEY NOT NULL,
  "context_id" uuid UNIQUE NOT NULL,
  "request_fingerprint" text NOT NULL,
  "tenant_id" uuid NOT NULL,
  "github_installation_id" text NOT NULL,
  "github_repository_id" text NOT NULL,
  "pipeline_id" uuid,
  "scope_revision" text NOT NULL,
  "incident_id" uuid NOT NULL,
  "execution_id" uuid NOT NULL,
  "evidence_ids" uuid[] NOT NULL,
  "summary" text NOT NULL,
  "correlation_id" text NOT NULL,
  "context_created_at" timestamptz NOT NULL,
  "signals" jsonb NOT NULL,
  "created_at" timestamptz NOT NULL,
  CONSTRAINT "correlated_context_receipts_identity_ck" CHECK (operation_id = context_id),
  CONSTRAINT "correlated_context_receipts_fingerprint_ck" CHECK (request_fingerprint ~ '^v1:sha256:[0-9a-f]{64}$'),
  CONSTRAINT "correlated_context_receipts_installation_ck" CHECK (github_installation_id ~ '^[1-9][0-9]{0,31}$'),
  CONSTRAINT "correlated_context_receipts_repository_ck" CHECK (github_repository_id ~ '^[1-9][0-9]{0,31}$'),
  CONSTRAINT "correlated_context_receipts_scope_ck" CHECK (scope_revision ~ '^[A-Za-z0-9._:-]{1,128}$'),
  CONSTRAINT "correlated_context_receipts_evidence_ck" CHECK (cardinality(evidence_ids) > 0),
  CONSTRAINT "correlated_context_receipts_summary_ck" CHECK (btrim(summary) <> ''),
  CONSTRAINT "correlated_context_receipts_correlation_ck" CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
  CONSTRAINT "correlated_context_receipts_signals_shape_ck" CHECK (CASE WHEN jsonb_typeof(signals) = 'array' THEN jsonb_array_length(signals) > 0 AND jsonb_array_length(signals) = cardinality(evidence_ids) ELSE false END)
);

CREATE FUNCTION diagnosis.valid_correlated_signals(signals jsonb, evidence_ids uuid[]) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE SET search_path = pg_catalog AS $$
DECLARE item jsonb; member uuid; seen uuid[] := ARRAY[]::uuid[];
BEGIN
    IF signals IS NULL OR evidence_ids IS NULL OR jsonb_typeof(signals) <> 'array' THEN
        RETURN false;
    END IF;
    IF array_ndims(evidence_ids) IS DISTINCT FROM 1 OR array_lower(evidence_ids,1) IS DISTINCT FROM 1
       OR cardinality(evidence_ids) = 0 OR jsonb_array_length(signals) <> cardinality(evidence_ids) THEN
        RETURN false;
    END IF;
    FOR item IN SELECT value FROM jsonb_array_elements(signals) LOOP
        IF jsonb_typeof(item) <> 'object' THEN RETURN false; END IF;
        IF (SELECT count(*) FROM jsonb_object_keys(item)) <> 6
           OR NOT item ?& ARRAY['evidence_id','origin_context_id','correlation_id','stage','summary','content']
           OR EXISTS (SELECT 1 FROM jsonb_each(item) field WHERE jsonb_typeof(field.value) <> 'string') THEN
            RETURN false;
        END IF;
        member := (item->>'evidence_id')::uuid;
        PERFORM (item->>'origin_context_id')::uuid;
        IF member = ANY(seen) OR NOT member = ANY(evidence_ids)
           OR (item->>'correlation_id') !~ '^[A-Za-z0-9._:-]{1,128}$'
           OR (item->>'stage') NOT IN ('BUILD','TESTING','QUALITY_GATE','DEPLOYMENT','CONTAINER','INFRASTRUCTURE')
           OR (item->>'summary') !~ '[^[:space:]]' OR (item->>'content') !~ '[^[:space:]]' THEN
            RETURN false;
        END IF;
        seen := array_append(seen, member);
    END LOOP;
    RETURN (SELECT count(DISTINCT id) FROM unnest(evidence_ids) id) = cardinality(evidence_ids)
           AND NOT EXISTS (SELECT 1 FROM unnest(evidence_ids) id WHERE id IS NULL);
EXCEPTION WHEN invalid_text_representation THEN
    RETURN false;
END; $$;
ALTER TABLE diagnosis.correlated_context_receipts
ADD CONSTRAINT correlated_context_receipts_signals_membership_ck
CHECK (diagnosis.valid_correlated_signals(signals, evidence_ids));

CREATE FUNCTION diagnosis.reject_correlated_receipt_mutation() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$ BEGIN
    RAISE EXCEPTION 'Correlation context receipt is append-only'
      USING ERRCODE = '23514';
END; $$;
CREATE TRIGGER correlated_context_receipts_append_only
BEFORE UPDATE OR DELETE OR TRUNCATE ON diagnosis.correlated_context_receipts
FOR EACH STATEMENT EXECUTE FUNCTION diagnosis.reject_correlated_receipt_mutation();
