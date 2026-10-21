CREATE TABLE app_user (
 id BIGSERIAL PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
 role VARCHAR(32) NOT NULL CHECK (role IN ('admin','underwriter','support_specialist','borrower')),
 display_name VARCHAR(120) NOT NULL, phone VARCHAR(30), enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE borrower_profile (
 id BIGSERIAL PRIMARY KEY, user_id BIGINT UNIQUE REFERENCES app_user(id), legal_name VARCHAR(180) NOT NULL,
 trading_name VARCHAR(180), tax_identifier_last4 CHAR(4) NOT NULL, entity_type VARCHAR(50) NOT NULL,
 founded_on DATE, annual_revenue NUMERIC(19,2) NOT NULL, currency CHAR(3) NOT NULL DEFAULT 'USD',
 address_line1 VARCHAR(180) NOT NULL, city VARCHAR(80) NOT NULL, region VARCHAR(80) NOT NULL,
 postal_code VARCHAR(20) NOT NULL, owner_summary VARCHAR(1000), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE lending_product (
 id BIGSERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL, currency CHAR(3) NOT NULL,
 min_amount NUMERIC(19,2) NOT NULL, max_amount NUMERIC(19,2) NOT NULL,
 min_term_months INT NOT NULL, max_term_months INT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE risk_policy (
 id BIGSERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE, currency CHAR(3) NOT NULL,
 manual_review_threshold NUMERIC(19,2) NOT NULL, max_approval_amount NUMERIC(19,2) NOT NULL
);
CREATE TABLE loan_application (
 id BIGSERIAL PRIMARY KEY, reference VARCHAR(24) NOT NULL UNIQUE, borrower_id BIGINT NOT NULL REFERENCES borrower_profile(id),
 product_id BIGINT NOT NULL REFERENCES lending_product(id), requested_amount NUMERIC(19,2) NOT NULL,
 currency CHAR(3) NOT NULL, purpose VARCHAR(500) NOT NULL, term_months INT NOT NULL,
 status VARCHAR(32) NOT NULL, risk_tier VARCHAR(8), assigned_underwriter_id BIGINT REFERENCES app_user(id),
 submitted_at TIMESTAMPTZ, version INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE document_type (
 id BIGSERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL, category VARCHAR(32) NOT NULL,
 required BOOLEAN NOT NULL DEFAULT TRUE, active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE loan_document (
 id BIGSERIAL PRIMARY KEY, application_id BIGINT NOT NULL REFERENCES loan_application(id), document_type_id BIGINT NOT NULL REFERENCES document_type(id),
 status VARCHAR(32) NOT NULL, original_name VARCHAR(255), stored_name VARCHAR(100), media_type VARCHAR(100), size_bytes BIGINT,
 sha256 CHAR(64), borrower_reason VARCHAR(500), reviewer_id BIGINT REFERENCES app_user(id), expires_on DATE,
 uploaded_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE underwriting_note (
 id BIGSERIAL PRIMARY KEY, application_id BIGINT NOT NULL REFERENCES loan_application(id), author_id BIGINT NOT NULL REFERENCES app_user(id),
 body VARCHAR(4000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE borrower_message (
 id BIGSERIAL PRIMARY KEY, application_id BIGINT NOT NULL REFERENCES loan_application(id), sender_id BIGINT NOT NULL REFERENCES app_user(id),
 body VARCHAR(4000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE decision_record (
 id BIGSERIAL PRIMARY KEY, application_id BIGINT NOT NULL UNIQUE REFERENCES loan_application(id), decided_by BIGINT NOT NULL REFERENCES app_user(id),
 outcome VARCHAR(32) NOT NULL, approved_amount NUMERIC(19,2), currency CHAR(3) NOT NULL,
 risk_tier VARCHAR(8) NOT NULL, reasons VARCHAR(2000) NOT NULL, final_decision BOOLEAN NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE payment_account (
 id BIGSERIAL PRIMARY KEY, borrower_id BIGINT NOT NULL REFERENCES borrower_profile(id), bank_name VARCHAR(100) NOT NULL,
 account_last4 CHAR(4) NOT NULL, routing_last4 CHAR(4) NOT NULL, verified BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE notification (
 id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_user(id), title VARCHAR(160) NOT NULL,
 body VARCHAR(500) NOT NULL, link VARCHAR(500), read_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE audit_event (
 id BIGSERIAL PRIMARY KEY, actor_id BIGINT REFERENCES app_user(id), action VARCHAR(100) NOT NULL,
 resource_type VARCHAR(80) NOT NULL, resource_id VARCHAR(80), before_value JSONB, after_value JSONB,
 authorization_basis VARCHAR(300) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE OR REPLACE FUNCTION deny_audit_change() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'audit events are append-only'; END $$ LANGUAGE plpgsql;
CREATE TRIGGER audit_no_update BEFORE UPDATE OR DELETE ON audit_event FOR EACH ROW EXECUTE FUNCTION deny_audit_change();
CREATE TABLE user_session (
 token_hash CHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_user(id), expires_at TIMESTAMPTZ NOT NULL,
 revoked_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE password_reset (
 token_hash CHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_user(id), expires_at TIMESTAMPTZ NOT NULL,
 used_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE lead_import (
 id BIGSERIAL PRIMARY KEY, uploaded_by BIGINT NOT NULL REFERENCES app_user(id), original_name VARCHAR(255) NOT NULL,
 accepted_count INT NOT NULL, rejected_count INT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE outbox (
 id BIGSERIAL PRIMARY KEY, recipient VARCHAR(254) NOT NULL, subject VARCHAR(200) NOT NULL, body VARCHAR(4000) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'pending', created_at TIMESTAMPTZ NOT NULL DEFAULT now(), sent_at TIMESTAMPTZ
);
CREATE TABLE webhook_endpoint (
 id BIGSERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL, endpoint_url VARCHAR(500) NOT NULL, secret_hash CHAR(64) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE, created_by BIGINT NOT NULL REFERENCES app_user(id)
);
CREATE TABLE webhook_delivery (
 id BIGSERIAL PRIMARY KEY, webhook_id BIGINT NOT NULL REFERENCES webhook_endpoint(id), event_type VARCHAR(100) NOT NULL,
 payload JSONB NOT NULL, status_code INT, attempt_count INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX app_borrower_idx ON loan_application(borrower_id);
CREATE INDEX app_queue_idx ON loan_application(status, assigned_underwriter_id);
CREATE INDEX doc_application_idx ON loan_document(application_id);
CREATE INDEX audit_resource_idx ON audit_event(resource_type, resource_id, occurred_at DESC);

-- Password for all demo accounts: Welcome123! (BCrypt, mock identities only).
INSERT INTO app_user(email,password_hash,role,display_name,phone) VALUES
('admin@northstar.local','$2y$10$GPEswZVJlvZSXpDSc80.9OWw4hC7HrJ5whdYoGepwz5m5it1wr5m2','admin','Maya Chen','+1 555 0100'),
('underwriter@northstar.local','$2y$10$GPEswZVJlvZSXpDSc80.9OWw4hC7HrJ5whdYoGepwz5m5it1wr5m2','underwriter','Jon Bell','+1 555 0101'),
('support@northstar.local','$2y$10$GPEswZVJlvZSXpDSc80.9OWw4hC7HrJ5whdYoGepwz5m5it1wr5m2','support_specialist','Avery Singh','+1 555 0102'),
('borrower@northstar.local','$2y$10$GPEswZVJlvZSXpDSc80.9OWw4hC7HrJ5whdYoGepwz5m5it1wr5m2','borrower','Elena Ortiz','+1 555 0103');
INSERT INTO app_user(email,password_hash,role,display_name)
SELECT 'borrower'||n||'@northstar.local','$2y$10$GPEswZVJlvZSXpDSc80.9OWw4hC7HrJ5whdYoGepwz5m5it1wr5m2','borrower','Demo Borrower '||n FROM generate_series(2,12) n;
INSERT INTO borrower_profile(user_id,legal_name,trading_name,tax_identifier_last4,entity_type,founded_on,annual_revenue,address_line1,city,region,postal_code,owner_summary)
SELECT CASE WHEN n=1 THEN 4 ELSE n+3 END, 'Demo Business '||n||' LLC', 'Business '||n, lpad((1200+n)::text,4,'0'),
 'LLC', DATE '2014-01-01'+(n*120), (310000+n*57500)::numeric, n||' Market Street',
 CASE WHEN n%3=0 THEN 'Austin' WHEN n%3=1 THEN 'Denver' ELSE 'Portland' END,
 CASE WHEN n%3=0 THEN 'TX' WHEN n%3=1 THEN 'CO' ELSE 'OR' END, '1000'||n, 'Two owners; mock profile for testing.'
FROM generate_series(1,12) n;
INSERT INTO lending_product(name,currency,min_amount,max_amount,min_term_months,max_term_months) VALUES
('Working Capital Flex','USD',10000,250000,6,36),('Equipment Term Loan','USD',25000,500000,12,60),('Expansion Loan','USD',50000,750000,12,72);
INSERT INTO risk_policy(name,currency,manual_review_threshold,max_approval_amount) VALUES ('Standard underwriting policy','USD',250000,750000);
INSERT INTO document_type(name,category,required) VALUES
('Bank statements — 3 months','financial',true),('Business tax return','tax',true),('Government identity','identity',true),('Formation certificate','business',true),('Additional supporting document','financial',false);
INSERT INTO loan_application(reference,borrower_id,product_id,requested_amount,currency,purpose,term_months,status,risk_tier,assigned_underwriter_id,submitted_at,created_at,updated_at)
SELECT 'NS-2026-'||lpad(n::text,4,'0'), ((n-1)%12)+1, ((n-1)%3)+1, (25000+n*12500)::numeric, 'USD',
 CASE n%4 WHEN 0 THEN 'Purchase production equipment' WHEN 1 THEN 'Seasonal working capital' WHEN 2 THEN 'Open a second location' ELSE 'Inventory expansion' END,
 12+(n%4)*12,
 (ARRAY['draft','submitted','in_review','needs_documents','counteroffered','approved','rejected','withdrawn'])[((n-1)%8)+1],
 (ARRAY['A','B','C','D'])[((n-1)%4)+1], CASE WHEN n%3=0 THEN NULL ELSE 2 END,
 CASE WHEN ((n-1)%8)+1=1 THEN NULL ELSE now()-(n||' days')::interval END, now()-(n||' days')::interval, now()-(n||' hours')::interval
FROM generate_series(1,16) n;
INSERT INTO loan_document(application_id,document_type_id,status,original_name,stored_name,media_type,size_bytes,sha256,borrower_reason,reviewer_id,expires_on,uploaded_at)
SELECT ((n-1)%16)+1, ((n-1)%5)+1,
 (ARRAY['requested','uploaded','accepted','rejected','expired','needs_resubmission'])[((n-1)%6)+1],
 CASE WHEN n%6=1 THEN NULL ELSE 'mock-document-'||n||'.pdf' END,
 CASE WHEN n%6=1 THEN NULL ELSE 'seed-'||n||'.pdf' END, CASE WHEN n%6=1 THEN NULL ELSE 'application/pdf' END,
 CASE WHEN n%6=1 THEN NULL ELSE 12000+n*100 END, CASE WHEN n%6=1 THEN NULL ELSE repeat('a',64) END,
 CASE WHEN n%6 IN (4,0) THEN 'The file is incomplete; upload all pages.' ELSE NULL END,
 CASE WHEN n%6 IN (3,4,5,0) THEN 2 ELSE NULL END, current_date+(90-n), CASE WHEN n%6=1 THEN NULL ELSE now()-(n||' hours')::interval END
FROM generate_series(1,40) n;
INSERT INTO borrower_message(application_id,sender_id,body,created_at)
SELECT ((n-1)%16)+1, CASE WHEN n%3=0 THEN 3 ELSE 4 END, 'Mock borrower update '||n||': please review the latest application activity.', now()-(n||' hours')::interval FROM generate_series(1,20) n;
INSERT INTO underwriting_note(application_id,author_id,body,created_at)
SELECT n,2,'Internal mock risk note for application '||n||'. Verify cash-flow consistency before decision.',now()-(n||' hours')::interval FROM generate_series(2,15,2) n;
INSERT INTO decision_record(application_id,decided_by,outcome,approved_amount,currency,risk_tier,reasons,final_decision,created_at)
SELECT n,2,CASE WHEN n%3=0 THEN 'rejected' WHEN n%3=1 THEN 'approved' ELSE 'counteroffer' END,
 CASE WHEN n%3=0 THEN NULL ELSE 20000+n*10000 END,'USD',(ARRAY['A','B','C'])[(n%3)+1],
 'Mock decision based on verified revenue and debt service coverage.',n%3<>2,now()-(n||' hours')::interval FROM generate_series(1,8) n;
INSERT INTO payment_account(borrower_id,bank_name,account_last4,routing_last4,verified) SELECT id,'Northstar Demo Bank',lpad((2000+id)::text,4,'0'),'0210',id%2=0 FROM borrower_profile;
INSERT INTO notification(user_id,title,body,link)
SELECT CASE WHEN n%3=0 THEN 2 ELSE 4 END,'Application update','There is new activity on loan application NS-2026-'||lpad((((n-1)%16)+1)::text,4,'0'),'/applications/'||(((n-1)%16)+1) FROM generate_series(1,18) n;
INSERT INTO audit_event(actor_id,action,resource_type,resource_id,before_value,after_value,authorization_basis,occurred_at)
SELECT CASE WHEN n%4=0 THEN 1 WHEN n%4=1 THEN 2 WHEN n%4=2 THEN 3 ELSE 4 END,'application.view','loan_application',(((n-1)%16)+1)::text,NULL,jsonb_build_object('result','allowed'),'role and object scope resolved by decision tier',now()-(n||' hours')::interval FROM generate_series(1,30) n;
