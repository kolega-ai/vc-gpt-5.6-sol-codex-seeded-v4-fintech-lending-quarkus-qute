-- The seed loader replays the same explicit edges as the application service so every
-- non-draft fixture status is produced by a named action rather than an unreachable insert.
SELECT setval('audit_event_id_seq',(SELECT max(id)+100 FROM audit_event),false);
CREATE TEMP TABLE seed_app_target AS SELECT id,status FROM loan_application;
UPDATE loan_application SET status='draft';
CREATE OR REPLACE FUNCTION seed_app_step(p_id BIGINT,p_target VARCHAR) RETURNS void AS $$
DECLARE current_status VARCHAR; next_status VARCHAR;
BEGIN
 SELECT status INTO current_status FROM loan_application WHERE id=p_id;
 WHILE current_status<>p_target LOOP
  next_status:=CASE
   WHEN current_status='draft' AND p_target='withdrawn' THEN 'withdrawn'
   WHEN current_status='draft' THEN 'submitted'
   WHEN current_status='submitted' AND p_target='needs_documents' THEN 'needs_documents'
   WHEN current_status='submitted' THEN 'in_review'
   WHEN current_status='in_review' THEN p_target
   ELSE NULL END;
  IF next_status IS NULL OR NOT ((current_status,next_status) IN (('draft','submitted'),('draft','withdrawn'),('submitted','in_review'),('submitted','needs_documents'),('in_review','counteroffered'),('in_review','approved'),('in_review','rejected'))) THEN RAISE EXCEPTION 'Invalid seed edge % -> %',current_status,next_status; END IF;
  UPDATE loan_application SET status=next_status WHERE id=p_id;
  INSERT INTO audit_event(actor_id,action,resource_type,resource_id,before_value,after_value,authorization_basis)
   VALUES (1,'seed.application.transition','loan_application',p_id::text,jsonb_build_object('status',current_status),jsonb_build_object('status',next_status),'seed replay through declared workflow edge');
  current_status:=next_status;
 END LOOP;
END $$ LANGUAGE plpgsql;
DO $$ DECLARE r RECORD; BEGIN FOR r IN SELECT * FROM seed_app_target LOOP PERFORM seed_app_step(r.id,r.status); END LOOP; END $$;
DROP FUNCTION seed_app_step(BIGINT,VARCHAR);

CREATE TEMP TABLE seed_doc_target AS SELECT id,status FROM loan_document;
UPDATE loan_document SET status='requested';
CREATE OR REPLACE FUNCTION seed_doc_step(p_id BIGINT,p_target VARCHAR) RETURNS void AS $$
DECLARE current_status VARCHAR:='requested'; next_status VARCHAR;
BEGIN
 WHILE current_status<>p_target LOOP
  next_status:=CASE WHEN current_status='requested' THEN 'uploaded' WHEN current_status='uploaded' AND p_target IN ('accepted','expired') THEN 'accepted' WHEN current_status='uploaded' THEN p_target WHEN current_status='accepted' AND p_target='expired' THEN 'expired' ELSE NULL END;
  IF next_status IS NULL OR NOT ((current_status,next_status) IN (('requested','uploaded'),('uploaded','accepted'),('uploaded','rejected'),('uploaded','needs_resubmission'),('accepted','expired'))) THEN RAISE EXCEPTION 'Invalid seed document edge % -> %',current_status,next_status; END IF;
  UPDATE loan_document SET status=next_status WHERE id=p_id;
  INSERT INTO audit_event(actor_id,action,resource_type,resource_id,before_value,after_value,authorization_basis)
   VALUES (1,'seed.document.transition','loan_document',p_id::text,jsonb_build_object('status',current_status),jsonb_build_object('status',next_status),'seed replay through declared workflow edge');
  current_status:=next_status;
 END LOOP;
END $$ LANGUAGE plpgsql;
DO $$ DECLARE r RECORD; BEGIN FOR r IN SELECT * FROM seed_doc_target LOOP PERFORM seed_doc_step(r.id,r.status); END LOOP; END $$;
DROP FUNCTION seed_doc_step(BIGINT,VARCHAR);
