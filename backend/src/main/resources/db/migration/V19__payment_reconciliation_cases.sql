-- Case đối soát thủ công cho giao dịch PAY/Pending quá hạn.
-- Một transaction tối đa một case; case đóng khi Admin reconcile hoặc IPN chốt giao dịch.
CREATE TABLE payment_reconciliation_cases (
    id                     BIGSERIAL PRIMARY KEY,
    transaction_id         BIGINT NOT NULL UNIQUE REFERENCES payment_transactions (id),
    invoice_id             BIGINT NOT NULL,
    status                 VARCHAR(20) NOT NULL DEFAULT 'Open',
    detected_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    notified_at            TIMESTAMPTZ,
    resolved_at            TIMESTAMPTZ,
    resolved_by_email      VARCHAR(255),
    resolution             VARCHAR(20),
    gateway_transaction_no VARCHAR(100),
    paid_at                TIMESTAMPTZ,
    note                   TEXT,
    evidence_url           TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payment_reconciliation_cases_status_check
        CHECK (status IN ('Open', 'Resolved')),
    CONSTRAINT payment_reconciliation_cases_resolution_check
        CHECK (resolution IS NULL OR resolution IN ('Success', 'Failed', 'Duplicate'))
);

CREATE INDEX idx_payment_reconciliation_cases_open
    ON payment_reconciliation_cases (detected_at)
    WHERE status = 'Open';
