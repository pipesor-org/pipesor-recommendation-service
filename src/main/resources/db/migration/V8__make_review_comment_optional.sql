-- Reviewer notes are optional for CONFIRMED and CORRECTED (the actual cause already justifies a correction);
-- a REJECTED review, which proposes no alternative cause, still requires its justification.
ALTER TABLE diagnosis.human_reviews ALTER COLUMN comment DROP NOT NULL;
ALTER TABLE diagnosis.human_reviews DROP CONSTRAINT human_reviews_comment_check;
ALTER TABLE diagnosis.human_reviews ADD CONSTRAINT human_reviews_comment_check
    CHECK ((comment IS NULL OR btrim(comment) <> '') AND (decision <> 'REJECTED' OR comment IS NOT NULL));
