CREATE TABLE borrower_lead (
 id BIGSERIAL PRIMARY KEY, email VARCHAR(254) NOT NULL, contact_name VARCHAR(120) NOT NULL,
 business_name VARCHAR(180) NOT NULL, status VARCHAR(24) NOT NULL DEFAULT 'invited',
 imported_by BIGINT NOT NULL REFERENCES app_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX borrower_lead_email_idx ON borrower_lead(lower(email));
