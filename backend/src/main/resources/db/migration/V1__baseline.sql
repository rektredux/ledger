-- Ledger baseline schema (SQLite).
--
-- Conventions that apply to every table here:
--
--  * Money is stored as an INTEGER count of minor units (paise for INR) in a
--    column suffixed `_minor`, paired with an ISO-4217 `currency`. There is no
--    floating point anywhere in this schema.
--  * `amount_minor` is always a POSITIVE magnitude. Direction is derived from
--    the transaction `type` (see TransactionType / LedgerMath in the backend),
--    never from the sign of the stored amount. This keeps "is this an expense"
--    a single, testable decision instead of a sign convention smeared across
--    the codebase.
--  * Dates and timestamps are stored as fixed-width ISO-8601 TEXT. SQLite has
--    no native date type, and ISO-8601 text sorts and range-compares correctly
--    lexicographically, so BETWEEN queries on `occurred_on` are exact. The
--    PostgreSQL migration path (see README) rewrites these columns to DATE /
--    TIMESTAMPTZ and drops the attribute converters.
--  * Primary keys are UUID TEXT rather than autoincrement integers so that IDs
--    survive JSON backup/restore and so source->canonical links stay stable
--    across an export/import round trip.

-- Note: `PRAGMA foreign_keys = ON` is deliberately NOT set here. The pragma is
-- per-connection, so setting it inside a migration would only affect Flyway's
-- own connection. It is configured for every pooled connection via
-- spring.datasource.hikari.connection-init-sql in application.properties.

-- ---------------------------------------------------------------------------
-- Accounts and payment instruments
-- ---------------------------------------------------------------------------

CREATE TABLE account (
    id                  TEXT PRIMARY KEY,
    name                TEXT    NOT NULL,
    -- CASH / BANK / CREDIT_CARD / PREPAID_WALLET
    type                TEXT    NOT NULL CHECK (type IN ('CASH','BANK','CREDIT_CARD','PREPAID_WALLET')),
    currency            TEXT    NOT NULL DEFAULT 'INR',
    institution         TEXT,
    -- Last 4 digits / UPI handle fragment used to attribute a parsed message to
    -- this account. Never the full card or account number.
    instrument_hint     TEXT,
    -- Balance the user states as the starting point. Derived balances are
    -- opening_balance + ledger movement, and are only as complete as ingestion.
    opening_balance_minor BIGINT NOT NULL DEFAULT 0,

    -- Credit-card-only configuration. NULL on other account types.
    credit_limit_minor  BIGINT,
    statement_day       INTEGER CHECK (statement_day BETWEEN 1 AND 31),
    due_day             INTEGER CHECK (due_day BETWEEN 1 AND 31),

    archived            BOOLEAN NOT NULL DEFAULT 0 CHECK (archived IN (0,1)),
    created_at          TEXT    NOT NULL,
    updated_at          TEXT    NOT NULL
);

CREATE INDEX idx_account_type ON account (type, archived);
-- Instrument hints are how parsers attribute a message to an account, so they
-- must be unambiguous within an institution.
CREATE UNIQUE INDEX uq_account_instrument
    ON account (institution, instrument_hint)
    WHERE instrument_hint IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Categories
-- ---------------------------------------------------------------------------

CREATE TABLE category (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    parent_id   TEXT REFERENCES category (id) ON DELETE CASCADE,
    -- Categories that represent money moving between owned accounts or settling
    -- a liability must never be counted as spending.
    excluded_from_spending BOOLEAN NOT NULL DEFAULT 0 CHECK (excluded_from_spending IN (0,1)),
    system      BOOLEAN NOT NULL DEFAULT 0 CHECK (system IN (0,1)),
    icon        TEXT,
    created_at  TEXT NOT NULL
);

CREATE UNIQUE INDEX uq_category_name_parent ON category (name, IFNULL(parent_id, ''));
CREATE INDEX idx_category_parent ON category (parent_id);

-- ---------------------------------------------------------------------------
-- Canonical transactions: one row per real financial event
-- ---------------------------------------------------------------------------

CREATE TABLE canonical_transaction (
    id                  TEXT PRIMARY KEY,

    amount_minor        BIGINT NOT NULL CHECK (amount_minor >= 0),
    currency            TEXT    NOT NULL DEFAULT 'INR',

    occurred_on         TEXT    NOT NULL,          -- yyyy-MM-dd
    occurred_at_time    TEXT,                      -- HH:mm:ss, when the source gives it
    posted_on           TEXT,                       -- set when a pending txn posts

    -- EXPENSE / INCOME / REFUND / REVERSAL / TRANSFER / CARD_PAYMENT
    type                TEXT    NOT NULL CHECK (type IN
                            ('EXPENSE','INCOME','REFUND','REVERSAL','TRANSFER','CARD_PAYMENT')),
    status              TEXT    NOT NULL DEFAULT 'POSTED'
                            CHECK (status IN ('PENDING','POSTED','VOID')),

    merchant_raw        TEXT,                      -- exactly as the source wrote it
    merchant            TEXT,                      -- normalized display name

    category_id         TEXT REFERENCES category (id) ON DELETE SET NULL,
    -- How the category was arrived at, so the UI can distinguish a fact from a
    -- guess: RULE / LEARNED / USER / UNCATEGORIZED
    category_source     TEXT    NOT NULL DEFAULT 'UNCATEGORIZED'
                            CHECK (category_source IN ('RULE','LEARNED','USER','UNCATEGORIZED')),
    category_confidence REAL    NOT NULL DEFAULT 0 CHECK (category_confidence BETWEEN 0 AND 1),

    -- Primary account affected. For TRANSFER and CARD_PAYMENT this is the
    -- account money leaves; counter_account_id is where it lands.
    account_id          TEXT REFERENCES account (id) ON DELETE SET NULL,
    counter_account_id  TEXT REFERENCES account (id) ON DELETE SET NULL,

    description         TEXT,
    notes               TEXT,                      -- user-authored only
    external_reference  TEXT,                      -- UPI ref / bank txn id, when present

    -- Relationships between events.
    refund_of_id        TEXT REFERENCES canonical_transaction (id) ON DELETE SET NULL,
    reverses_id         TEXT REFERENCES canonical_transaction (id) ON DELETE SET NULL,

    -- UNRECONCILED / MATCHED / NEEDS_REVIEW / CONFIRMED
    reconciliation_status TEXT  NOT NULL DEFAULT 'UNRECONCILED'
                            CHECK (reconciliation_status IN
                            ('UNRECONCILED','MATCHED','NEEDS_REVIEW','CONFIRMED')),

    -- How this row came to exist: AUTOMATIC (provider ingestion), CSV_IMPORT,
    -- MANUAL (user-entered exception), RECURRING.
    origin              TEXT    NOT NULL DEFAULT 'AUTOMATIC'
                            CHECK (origin IN ('AUTOMATIC','CSV_IMPORT','MANUAL','RECURRING')),

    created_at          TEXT    NOT NULL,
    updated_at          TEXT    NOT NULL,

    -- A transfer/card payment must name the other side; a non-transfer must not
    -- point at itself.
    CHECK (type NOT IN ('TRANSFER','CARD_PAYMENT') OR counter_account_id IS NOT NULL),
    CHECK (counter_account_id IS NULL OR counter_account_id <> account_id)
);

CREATE INDEX idx_txn_occurred      ON canonical_transaction (occurred_on);
CREATE INDEX idx_txn_type_date     ON canonical_transaction (type, occurred_on);
CREATE INDEX idx_txn_account_date  ON canonical_transaction (account_id, occurred_on);
CREATE INDEX idx_txn_category      ON canonical_transaction (category_id);
CREATE INDEX idx_txn_merchant      ON canonical_transaction (merchant);
CREATE INDEX idx_txn_status        ON canonical_transaction (status);
CREATE INDEX idx_txn_recon         ON canonical_transaction (reconciliation_status);
CREATE INDEX idx_txn_refund_of     ON canonical_transaction (refund_of_id);
CREATE INDEX idx_txn_reverses      ON canonical_transaction (reverses_id);

-- An external reference, when a provider supplies one, uniquely identifies the
-- financial event. This index is the first and strongest line of defence
-- against re-ingesting the same transaction.
CREATE UNIQUE INDEX uq_txn_external_reference
    ON canonical_transaction (account_id, external_reference)
    WHERE external_reference IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Provider connections and synchronization state
--
-- Declared before source_record because source_record references it. SQLite
-- tolerates forward references, but PostgreSQL does not, and this file is the
-- template for the PostgreSQL migration.
-- ---------------------------------------------------------------------------

CREATE TABLE provider_connection (
    id                  TEXT PRIMARY KEY,
    provider            TEXT NOT NULL,
    -- Which account/mailbox this connection represents, for display only.
    display_name        TEXT,
    -- NOT_CONFIGURED / DISCONNECTED / CONNECTED / NEEDS_REAUTH / ERROR
    status              TEXT NOT NULL DEFAULT 'DISCONNECTED'
                            CHECK (status IN ('NOT_CONFIGURED','DISCONNECTED','CONNECTED',
                                              'NEEDS_REAUTH','ERROR')),

    -- OAuth material, encrypted at rest with AES-GCM (see TokenCipher). The
    -- plaintext refresh token is never written to this table or to logs.
    access_token_enc    TEXT,
    refresh_token_enc   TEXT,
    access_token_expires_at TEXT,
    granted_scopes      TEXT,

    -- Incremental sync cursor. For Gmail this is the mailbox historyId; the
    -- provider decides the format.
    sync_cursor         TEXT,
    -- Oldest date we have attempted to backfill, so a historical import can be
    -- resumed after a restart rather than restarted.
    backfill_through    TEXT,
    backfill_complete   BOOLEAN NOT NULL DEFAULT 0 CHECK (backfill_complete IN (0,1)),

    last_success_at     TEXT,
    last_attempt_at     TEXT,
    last_error          TEXT,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    sync_enabled        BOOLEAN NOT NULL DEFAULT 1 CHECK (sync_enabled IN (0,1)),

    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

CREATE UNIQUE INDEX uq_provider_connection ON provider_connection (provider);

CREATE TABLE sync_run (
    id                  TEXT PRIMARY KEY,
    provider_connection_id TEXT NOT NULL REFERENCES provider_connection (id) ON DELETE CASCADE,
    -- BACKFILL / INCREMENTAL / MANUAL
    mode                TEXT NOT NULL,
    -- RUNNING / SUCCESS / PARTIAL / FAILED
    status              TEXT NOT NULL DEFAULT 'RUNNING',
    started_at          TEXT NOT NULL,
    finished_at         TEXT,
    messages_examined   INTEGER NOT NULL DEFAULT 0,
    records_created     INTEGER NOT NULL DEFAULT 0,
    records_skipped     INTEGER NOT NULL DEFAULT 0,
    transactions_created INTEGER NOT NULL DEFAULT 0,
    transactions_linked INTEGER NOT NULL DEFAULT 0,
    queued_for_review   INTEGER NOT NULL DEFAULT 0,
    parse_failures      INTEGER NOT NULL DEFAULT 0,
    error               TEXT,
    cursor_before       TEXT,
    cursor_after        TEXT
);

CREATE INDEX idx_sync_run_conn ON sync_run (provider_connection_id, started_at DESC);

-- ---------------------------------------------------------------------------
-- Source records: one row per observation from a provider
-- ---------------------------------------------------------------------------

CREATE TABLE source_record (
    id                  TEXT PRIMARY KEY,

    -- GMAIL / SIMULATED / CSV / STATEMENT / MANUAL
    provider            TEXT    NOT NULL,
    -- Provider-scoped stable id (Gmail message id, CSV row hash, ...). The
    -- unique index below is what makes repeated synchronization idempotent.
    provider_record_id  TEXT    NOT NULL,
    provider_connection_id TEXT REFERENCES provider_connection (id) ON DELETE SET NULL,

    -- PENDING_PARSE / PARSED / PARSE_FAILED / LINKED / NEEDS_REVIEW / IGNORED
    status              TEXT    NOT NULL DEFAULT 'PENDING_PARSE'
                            CHECK (status IN ('PENDING_PARSE','PARSED','PARSE_FAILED',
                                              'LINKED','NEEDS_REVIEW','IGNORED')),

    -- The canonical event this observation was attributed to. NULL until the
    -- record is parsed and matched. Many source records -> one transaction.
    canonical_transaction_id TEXT REFERENCES canonical_transaction (id) ON DELETE SET NULL,

    -- Evidence. We keep enough to re-derive or correct a bad match, but we do
    -- NOT store raw email bodies: see `redacted_excerpt`.
    sender              TEXT,
    subject             TEXT,
    received_at         TEXT,                      -- ISO-8601 instant
    -- A short, redacted fragment retained purely so a user can understand why a
    -- record parsed the way it did. Account numbers, OTPs and long digit runs
    -- are masked before this is written.
    redacted_excerpt    TEXT,
    -- Hash of the full original content. Lets us detect that we have seen
    -- identical content before without retaining the content itself.
    content_hash        TEXT,

    -- Extracted (not inferred) fields. NULL means "the source did not say".
    extracted_amount_minor   BIGINT,
    extracted_currency       TEXT,
    extracted_occurred_on    TEXT,
    extracted_merchant       TEXT,
    extracted_type           TEXT,
    -- PENDING / POSTED, where the source distinguishes an authorisation from a
    -- settlement. Stored rather than recomputed because the message body is not
    -- retained, so a retry could not derive it again.
    extracted_status         TEXT,
    extracted_instrument_hint TEXT,
    extracted_reference      TEXT,
    -- Which named parser handled it, and how sure it is.
    parser_id           TEXT,
    parse_confidence    REAL CHECK (parse_confidence IS NULL OR parse_confidence BETWEEN 0 AND 1),
    parse_error         TEXT,

    attempt_count       INTEGER NOT NULL DEFAULT 0,
    last_attempt_at     TEXT,
    -- Transient failures back off instead of spinning; this is when the record
    -- becomes eligible for another attempt.
    next_attempt_at     TEXT,

    created_at          TEXT    NOT NULL,
    updated_at          TEXT    NOT NULL
);

-- Idempotency: a provider record is ingested at most once, ever.
CREATE UNIQUE INDEX uq_source_provider_record
    ON source_record (provider, provider_record_id);
CREATE INDEX idx_source_status       ON source_record (status, next_attempt_at);
CREATE INDEX idx_source_canonical    ON source_record (canonical_transaction_id);
CREATE INDEX idx_source_content_hash ON source_record (content_hash);
CREATE INDEX idx_source_received     ON source_record (received_at);

-- ---------------------------------------------------------------------------
-- Match decisions: the audit trail for deduplication
-- ---------------------------------------------------------------------------

CREATE TABLE match_decision (
    id                  TEXT PRIMARY KEY,
    source_record_id    TEXT NOT NULL REFERENCES source_record (id) ON DELETE CASCADE,
    -- The transaction this decision concerns. For CREATED_NEW it is the new
    -- transaction; for LINKED_EXISTING the one linked to.
    canonical_transaction_id TEXT REFERENCES canonical_transaction (id) ON DELETE SET NULL,

    -- CREATED_NEW / LINKED_EXISTING / QUEUED_FOR_REVIEW / UPDATED_PENDING_TO_POSTED
    -- / REJECTED_DUPLICATE_CONTENT / UNLINKED_BY_USER / CONFIRMED_BY_USER
    decision            TEXT NOT NULL,
    -- STRONG_REFERENCE / CONTENT_HASH / HEURISTIC_SCORE / USER
    strategy            TEXT NOT NULL,
    score               REAL,
    -- Human-readable reasoning, e.g. "amount=exact, date=+1d, merchant=0.91,
    -- account=exact". This is what the review queue shows the user.
    rationale           TEXT,
    decided_at          TEXT NOT NULL,
    decided_by          TEXT NOT NULL DEFAULT 'SYSTEM'
);

CREATE INDEX idx_match_source     ON match_decision (source_record_id);
CREATE INDEX idx_match_txn        ON match_decision (canonical_transaction_id);
CREATE INDEX idx_match_decided_at ON match_decision (decided_at);

-- ---------------------------------------------------------------------------
-- Review queue: ambiguity is parked here, never silently resolved or dropped
-- ---------------------------------------------------------------------------

CREATE TABLE review_item (
    id                  TEXT PRIMARY KEY,
    -- AMBIGUOUS_MATCH / PARSE_FAILURE / UNKNOWN_CATEGORY / UNKNOWN_ACCOUNT
    -- / RECONCILIATION_GAP
    kind                TEXT NOT NULL,
    -- OPEN / RESOLVED / DISMISSED
    status              TEXT NOT NULL DEFAULT 'OPEN'
                            CHECK (status IN ('OPEN','RESOLVED','DISMISSED')),
    source_record_id    TEXT REFERENCES source_record (id) ON DELETE CASCADE,
    canonical_transaction_id TEXT REFERENCES canonical_transaction (id) ON DELETE CASCADE,
    -- JSON array of candidate transaction ids with their scores and rationale.
    candidates_json     TEXT,
    summary             TEXT NOT NULL,
    created_at          TEXT NOT NULL,
    resolved_at         TEXT,
    resolution          TEXT
);

CREATE INDEX idx_review_status ON review_item (status, kind, created_at);
CREATE INDEX idx_review_source ON review_item (source_record_id);

-- ---------------------------------------------------------------------------
-- Categorization rules and merchant normalization
-- ---------------------------------------------------------------------------

CREATE TABLE merchant_alias (
    id          TEXT PRIMARY KEY,
    -- Lowercased pattern matched against the normalized merchant string.
    pattern     TEXT NOT NULL,
    -- EXACT / CONTAINS / REGEX
    match_type  TEXT NOT NULL DEFAULT 'CONTAINS' CHECK (match_type IN ('EXACT','CONTAINS','REGEX')),
    canonical_name TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

CREATE UNIQUE INDEX uq_merchant_alias ON merchant_alias (pattern, match_type);

CREATE TABLE category_rule (
    id          TEXT PRIMARY KEY,
    -- Matched against the normalized merchant, and optionally the description.
    pattern     TEXT NOT NULL,
    match_type  TEXT NOT NULL DEFAULT 'CONTAINS' CHECK (match_type IN ('EXACT','CONTAINS','REGEX')),
    category_id TEXT NOT NULL REFERENCES category (id) ON DELETE CASCADE,
    -- Higher priority wins. User-created rules are seeded above built-ins so a
    -- correction always beats the shipped default.
    priority    INTEGER NOT NULL DEFAULT 100,
    confidence  REAL    NOT NULL DEFAULT 0.9 CHECK (confidence BETWEEN 0 AND 1),
    -- BUILTIN rules ship with the app; LEARNED rules are written when the user
    -- recategorizes a transaction; USER rules are hand-authored in Settings.
    origin      TEXT    NOT NULL DEFAULT 'BUILTIN' CHECK (origin IN ('BUILTIN','LEARNED','USER')),
    enabled     BOOLEAN NOT NULL DEFAULT 1 CHECK (enabled IN (0,1)),
    hit_count   BIGINT NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE UNIQUE INDEX uq_category_rule ON category_rule (pattern, match_type, origin);
CREATE INDEX idx_category_rule_lookup ON category_rule (enabled, priority DESC);

-- ---------------------------------------------------------------------------
-- Budgets
-- ---------------------------------------------------------------------------

CREATE TABLE budget (
    id          TEXT PRIMARY KEY,
    -- NULL category_id means the overall monthly budget.
    category_id TEXT REFERENCES category (id) ON DELETE CASCADE,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency    TEXT NOT NULL DEFAULT 'INR',
    -- yyyy-MM for a specific month, or NULL for the recurring default budget.
    period      TEXT,
    warn_at_percent INTEGER NOT NULL DEFAULT 80 CHECK (warn_at_percent BETWEEN 1 AND 200),
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE UNIQUE INDEX uq_budget_scope ON budget (IFNULL(category_id,''), IFNULL(period,''));

-- ---------------------------------------------------------------------------
-- Recurring expectations (never themselves transactions)
-- ---------------------------------------------------------------------------

CREATE TABLE recurring_expense (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    amount_minor    BIGINT NOT NULL CHECK (amount_minor > 0),
    currency        TEXT NOT NULL DEFAULT 'INR',
    category_id     TEXT REFERENCES category (id) ON DELETE SET NULL,
    account_id      TEXT REFERENCES account (id) ON DELETE SET NULL,
    merchant        TEXT,
    -- MONTHLY / WEEKLY / YEARLY
    cadence         TEXT NOT NULL CHECK (cadence IN ('WEEKLY','MONTHLY','YEARLY')),
    -- Day of month (MONTHLY/YEARLY) or ISO day of week (WEEKLY).
    day_of_period   INTEGER NOT NULL,
    month_of_year   INTEGER CHECK (month_of_year BETWEEN 1 AND 12),
    starts_on       TEXT NOT NULL,
    ends_on         TEXT,
    active          BOOLEAN NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

-- One row per (definition, due date) occurrence. A row existing here means "we
-- expected this"; it is only ever a *forecast* until matched_transaction_id is
-- set by evidence that it actually happened.
CREATE TABLE recurring_occurrence (
    id                  TEXT PRIMARY KEY,
    recurring_expense_id TEXT NOT NULL REFERENCES recurring_expense (id) ON DELETE CASCADE,
    due_on              TEXT NOT NULL,
    -- EXPECTED / MATCHED / MISSED / SKIPPED
    status              TEXT NOT NULL DEFAULT 'EXPECTED'
                            CHECK (status IN ('EXPECTED','MATCHED','MISSED','SKIPPED')),
    matched_transaction_id TEXT REFERENCES canonical_transaction (id) ON DELETE SET NULL,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

-- Makes recurring generation idempotent: generating twice cannot duplicate.
CREATE UNIQUE INDEX uq_recurring_occurrence ON recurring_occurrence (recurring_expense_id, due_on);
CREATE INDEX idx_recurring_occurrence_due ON recurring_occurrence (due_on, status);

-- ---------------------------------------------------------------------------
-- Import batches (CSV / statement), for previews, reporting and undo
-- ---------------------------------------------------------------------------

CREATE TABLE import_batch (
    id              TEXT PRIMARY KEY,
    -- CSV_TRANSACTIONS / STATEMENT_RECONCILIATION / JSON_RESTORE
    kind            TEXT NOT NULL,
    filename        TEXT,
    account_id      TEXT REFERENCES account (id) ON DELETE SET NULL,
    -- PREVIEW / COMMITTED / FAILED
    status          TEXT NOT NULL DEFAULT 'PREVIEW',
    rows_total      INTEGER NOT NULL DEFAULT 0,
    rows_imported   INTEGER NOT NULL DEFAULT 0,
    rows_duplicate  INTEGER NOT NULL DEFAULT 0,
    rows_failed     INTEGER NOT NULL DEFAULT 0,
    -- JSON array of {row, field, message} so nothing is silently discarded.
    errors_json     TEXT,
    created_at      TEXT NOT NULL,
    committed_at    TEXT
);

CREATE INDEX idx_import_batch_created ON import_batch (created_at DESC);

-- ---------------------------------------------------------------------------
-- Key/value application settings
-- ---------------------------------------------------------------------------

CREATE TABLE app_setting (
    key         TEXT PRIMARY KEY,
    value       TEXT,
    updated_at  TEXT NOT NULL
);
