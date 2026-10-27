# Ledger — Local Automatic Expense Tracker

A personal expense tracking application that **automatically** collects financial transactions from Gmail and maintains one reliable, deduplicated ledger — without cloud accounts, paid services, or manual transcription.

---

## What it does

- Connects to Gmail with a read-only OAuth grant and continuously imports bank alerts, card notifications, UPI receipts and merchant confirmations
- Parses amounts, dates, merchants, instrument hints and references out of message bodies using local pattern matching — no AI API
- Deduplicates observations: a purchase that arrives in the merchant receipt, the bank alert and the statement becomes **one expense**, not three
- Categorises transactions automatically using a local rule engine that learns from corrections
- Tracks credit card liability correctly: a purchase is an expense; the bill payment is a liability settlement, never a second expense
- Shows a live dashboard, spending trends, category breakdowns, per-cycle card spending and budget progress — all from real database records
- Keeps everything on your device in a SQLite file

---

## Requirements

| Tool | Version |
|---|---|
| JDK | 21 (Microsoft, Temurin, Zulu, Amazon Corretto) |
| Maven | 3.8+ |
| Node.js | 18+ |
| npm | 9+ |

---

## Quick start

```bash
# 1. Start the backend (creates data/ledger.db automatically)
cd backend
mvn spring-boot:run
# Backend listens on http://127.0.0.1:8080

# 2. Start the frontend (in a second terminal)
cd frontend
npm install
npm run dev
# Frontend at http://localhost:5173

# 3. Open http://localhost:5173 in your browser
```

> **Note for developers:** If `mvn` is not on your PATH, or `~/.m2/repository` is
> read-only, use the wrapper script:
> ```bash
> cd /path/to/Ledger
> ./scripts/dev-mvn.sh spring-boot:run
> ```
> The wrapper locates your JDK 21 installation, routes Maven through the
> `localhost:54891` proxy, and uses a project-local repository at
> `backend/.mvn-repo`. See the script for details.

---

## Gmail setup

Gmail is the MVP source. It requires a Google Cloud OAuth client you own and control.

### Steps

1. Go to [console.cloud.google.com](https://console.cloud.google.com) → New project → "Ledger"
2. APIs & Services → Enable **Gmail API**
3. OAuth consent screen → External → fill in App name, email; add scope `gmail.readonly`
4. Credentials → Create OAuth client ID → Web application
   - Authorized redirect URI: `http://127.0.0.1:8080/api/sources/gmail/callback`
5. Note the **Client ID** and **Client Secret**

### Configure

Set these environment variables before starting the backend:

```bash
export GMAIL_CLIENT_ID=your-client-id.apps.googleusercontent.com
export GMAIL_CLIENT_SECRET=your-client-secret
```

Or add them to a `.env` file (never committed) and load it in your shell before `mvn spring-boot:run`.

### Connect

1. Open the app → **Data Sources**
2. Click **Connect Gmail**
3. Approve the consent screen — only the read-only scope is requested
4. The backend immediately starts a historical import (configurable via `LEDGER_BACKFILL_DAYS`, default 180 days)
5. Incremental sync runs every 15 minutes while the backend is running

### Disconnect / revoke

- App → Data Sources → Disconnect
- **Also visit** [myaccount.google.com/permissions](https://myaccount.google.com/permissions) and remove "Ledger" to be certain the grant is fully revoked

### What the app can and cannot observe

The Gmail connector reads transaction alerts and merchant receipts. It cannot observe:
- Cash payments (no electronic record)
- Banks and services that send no alerts, or whose alert format is not yet parsed
- Transactions that happened before the backfill start date
- Messages whose body the parser could not read (surfaced in the review queue)

Displayed totals are the sum of what was collected — presented as *observed* totals, not *complete* totals. The dashboard shows exactly what share of transactions was collected automatically and how many items need attention.

---

## Configuration

All settings are in `backend/src/main/resources/application.properties`. The most commonly overridden ones via environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `LEDGER_DB_PATH` | `./data/ledger.db` | SQLite file location |
| `LEDGER_PORT` | `8080` | Backend HTTP port |
| `LEDGER_BIND_ADDRESS` | `127.0.0.1` | Bind address (**do not expose publicly**) |
| `GMAIL_CLIENT_ID` | *(empty)* | Google OAuth client ID |
| `GMAIL_CLIENT_SECRET` | *(empty)* | Google OAuth client secret |
| `GMAIL_REDIRECT_URI` | `http://127.0.0.1:8080/api/sources/gmail/callback` | Must match Google Console |
| `LEDGER_BACKFILL_DAYS` | `180` | Historical import depth |
| `LEDGER_SYNC_INTERVAL` | `PT15M` | ISO-8601 duration |
| `LEDGER_SYNC_ENABLED` | `true` | Set to `false` to disable scheduler |
| `LEDGER_TOKEN_KEY` | *(auto-generated)* | Base64 AES-256 key for token encryption |
| `LEDGER_TOKEN_KEY_FILE` | `./data/token.key` | Auto-generated key file |
| `LEDGER_FRONTEND_URL` | `http://localhost:5173` | CORS and OAuth redirect |

---

## Database

### Location

`backend/data/ledger.db` (or `$LEDGER_DB_PATH`).

The file contains your complete financial history in **plaintext**. Restrict OS-level
file permissions. Store backups in an encrypted volume.

### Backup

1. Stop the backend
2. Copy `backend/data/ledger.db`, `backend/data/ledger.db-wal` and `backend/data/token.key`
   to secure storage
3. Restart

Or use the in-app JSON backup at Settings → Backup (excludes OAuth tokens — reconnect Gmail after a restore).

### PostgreSQL migration path

The application uses Spring Data JPA with Hibernate behind repository interfaces, and Flyway for migrations. To switch:

1. Add the PostgreSQL JDBC driver to `pom.xml`
2. Change `spring.datasource.url`, `spring.jpa.database-platform` and `spring.flyway.locations`
3. Rewrite the `V1__baseline.sql` migration to use PostgreSQL types (`DATE` for dates, `TIMESTAMPTZ` for instants, `BIGINT` for amounts)
4. Delete the three `JpaConverters` (ISO-8601 text dates are no longer needed)
5. Update `substr(occurred_on, 1, 7)` in the native monthly-trend query to `to_char(occurred_on, 'YYYY-MM')`

No other code changes are required because all data access goes through the repository interfaces.

---

## Security

- The backend binds to `127.0.0.1` by default. **Do not change this** without adding authentication, HTTPS and database protection.
- OAuth tokens are encrypted at rest with AES-256-GCM. The key is auto-generated in `data/token.key` (chmod 600).
- No Gmail passwords, bank passwords, PINs or card CVVs are stored or requested.
- Email message bodies are parsed in memory and discarded; what is stored is extracted fields, a SHA-256 content hash, and a short redacted excerpt (long digit runs and OTPs are masked).
- SQL and application logs intentionally exclude amounts and merchant names.

---

## API

All endpoints are under `/api`. When the Vite dev server is running, the proxy at `vite.config.ts` forwards `/api/*` to the backend — no manual URL changes are needed.

Key endpoints:

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/dashboard` | Dashboard payload |
| GET | `/api/transactions` | Filtered, paginated transaction list |
| GET | `/api/transactions/:id` | Transaction detail + evidence trail |
| POST | `/api/transactions` | Manual exception entry |
| POST | `/api/transactions/:id/category` | Recategorize + optionally learn |
| GET | `/api/accounts` | Account balances |
| GET | `/api/accounts/credit-cards` | Credit card summaries |
| GET | `/api/categories` | Category tree |
| GET | `/api/sources` | Data source health |
| GET | `/api/sources/gmail/status` | Gmail connection status |
| GET | `/api/sources/gmail/authorize` | Get OAuth URL |
| GET | `/api/sources/gmail/callback` | OAuth callback (Google redirects here) |
| POST | `/api/sources/gmail/sync` | Trigger manual sync |
| GET | `/api/sources/review` | Review queue |
| POST | `/api/data/csv/preview` | Validate CSV upload |
| POST | `/api/data/csv/commit/:batchId` | Commit CSV import |
| GET | `/api/data/csv/export` | Export as CSV |
| GET | `/api/data/backup` | Download JSON backup |
| POST | `/api/data/backup/restore` | Restore from backup |
| GET | `/api/data/budgets` | Budget progress |
| POST | `/api/data/budgets` | Create/update budget |

---

## Testing

### Backend

```bash
# From the project root
./scripts/dev-mvn.sh test
# Or with plain mvn if it's on PATH:
cd backend && mvn test
```

101 tests cover:
- Schema mapping: entity↔SQLite column agreement, exact paise precision, ISO-8601 date boundaries
- Accounting rules: credit card purchases vs bill payments, transfers, refunds/reversals, cash withdrawals, void transactions, month boundaries
- Ingestion pipeline: idempotency, two-sources-one-event deduplication, strong-reference matching, pending→posted settlement, ambiguous cases to review, non-transactional filtering, redaction
- Field extraction: amounts in Indian digit grouping, day-first date parsing, instrument hints, references, transaction type classification
- Gmail API client: MIME walking, HTML→text, base64url decoding

### Frontend

```bash
cd frontend
npm run typecheck    # TypeScript check
npm run build        # Production build (fails on TS errors)
```

---

## Architecture summary

```
frontend/                 React 19 + Vite + Tailwind CSS
  src/api.ts             Typed fetch client (amounts as integer paise)
  src/components/
    Layout.tsx            Monochrome design system: tokens, primitives, chart theme
  src/pages/             8 screens (Dashboard, Transactions, Accounts, Cards,
                          Budgets, Reports, Data Sources, Settings)

backend/src/main/java/com/ledger/
  account/              Account entities, service, statement-cycle calculator
  api/                  REST controllers, DTOs, exception handler
  budget/               Monthly budgets and progress
  categorize/           CategorizationEngine (rule-based), MerchantNormalizer
  category/             Category tree, cached in-memory
  common/               Money, JpaConverters, Redaction, TextSimilarity
  config/               CORS
  dataio/               CSV import/export, JSON backup/restore
  ingest/               IngestionService, MatchingEngine, MatchScore, RawObservation
  parse/                MessageParser SPI, BankAlertParser, MerchantReceiptParser,
                         CsvRowParser, TransactionTextExtractor, NonTransactionalFilter
  provider/gmail/       GmailProvider, GmailApiClient, GmailOAuthService, GmailProperties
  recurring/            Recurring expense definitions and occurrences
  report/               DashboardService, DateRange
  review/               ReviewItem entity, ReviewService (exception queue)
  security/             TokenCipher (AES-256-GCM)
  settings/             AppSetting entity
  source/               SourceRecord, ProviderConnection, TransactionSourceProvider SPI,
                         SimulatedProvider
  sync/                 SyncService, SyncScheduler, SyncRun
  txn/                  CanonicalTransaction, LedgerMath, TransactionService,
                         TransactionQuery (JPA Specification)

backend/src/main/resources/db/migration/
  V1__baseline.sql       Full schema (16 tables, all with FK constraints, partial indexes)
  V2__seed_categories_and_rules.sql  56 categories, 53 rules, 36 merchant aliases
```

---

## Known limitations

1. **Gmail only**: no official bank API integration yet
2. **SMS**: Android SMS collection needs a separate native app assessment; iOS SMS is not accessible to web apps
3. **Sync requires device on**: a local backend cannot sync while the machine is off or asleep
4. **Parser coverage**: not every bank's alert format is parsed; unrecognised messages are surfaced in the review queue
5. **No authentication**: the backend binds to localhost only; exposing it remotely without authentication would publish financial data
6. **OAuth approval**: the Gmail integration works as "testing mode" without Google verification; for use beyond the 100-user test-user list, Google app verification is required

---

## Startup sequence

1. Backend creates `data/` directory and `ledger.db`
2. Flyway runs migrations V1 and V2 (schema + seed data)
3. Spring validates entity↔schema mapping (fails fast on drift)
4. Stale sync runs from any previous process are closed
5. Scheduler fires after 30-second initial delay, then every 15 minutes
6. Frontend loads and polls `/api/dashboard` on every range change

---

## Licence

Private / personal use.
