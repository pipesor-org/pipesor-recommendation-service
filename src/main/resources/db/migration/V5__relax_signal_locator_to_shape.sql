-- Protected evidence 2.2: the database checks only the locator shape (prefix, lengths, line
-- range digits, digest). Member names, decoding and line-range rules are validated by the
-- application; this function no longer encodes a workflow-specific file name. Every valid 2.1
-- locator is still accepted. Persisted receipts are not rewritten. PostgreSQL caps regex
-- repetition at 255, so the 765-character member bound is enforced through the total length
-- (7 + 20 + 8 + 765 + 7 + 19 + 8 + 64 = 898 characters at most).
CREATE OR REPLACE FUNCTION diagnosis.valid_correlated_signals(signals jsonb, evidence_ids uuid[]) RETURNS boolean
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
        IF (SELECT count(*) FROM jsonb_object_keys(item)) <> (CASE WHEN item ? 'source_locator' THEN 7 ELSE 6 END)
           OR (item ? 'source_locator' AND ((item->>'source_locator') !~ '^v2:job:[1-9][0-9]{0,19}:member:(?:[A-Za-z0-9._~-]|%[0-9A-F]{2})+:lines:[1-9][0-9]{0,8}-[1-9][0-9]{0,8}:sha256:[0-9a-f]{64}$'
                                          OR char_length(item->>'source_locator') > 898))
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
