/**
 * Typed API client.
 *
 * All money values are `amountMinor: number` (integer paise) to avoid
 * floating-point accumulation. The helper `formatINR` formats for display only.
 */

// ---------- Money helpers --------------------------------------------------

export function formatINR(minor: number | null | undefined): string {
  if (minor == null) return '—';
  const major = minor / 100;
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 2,
  }).format(major);
}

export function formatMinor(minor: number | null | undefined): string {
  if (minor == null) return '—';
  return (minor / 100).toFixed(2);
}

// ---------- Common types ---------------------------------------------------

export interface PageDto<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface Totals {
  grossSpendingMinor: number;
  spendingOffsetsMinor: number;
  netSpendingMinor: number;
  incomeMinor: number;
  netCashFlowMinor: number;
  currency: string;
  previousNetSpendingMinor: number;
  previousIncomeMinor: number;
  spendingChangePercent: number | null;
  transactionCount: number;
}

export interface CategorySlice {
  categoryId: string | null;
  name: string;
  parentName: string | null;
  totalMinor: number;
  transactionCount: number;
  sharePercent: number;
}

export interface MerchantSlice {
  merchant: string;
  totalMinor: number;
  transactionCount: number;
}

export interface MonthPoint {
  period: string;
  spendingMinor: number;
  incomeMinor: number;
  netSpendingMinor: number;
}

export interface Coverage {
  connectedSourceCount: number;
  automaticCount: number;
  manualCount: number;
  importedCount: number;
  automaticSharePercent: number;
  uncategorizedCount: number;
  unattributedCount: number;
  pendingCount: number;
  openReviewCount: number;
  parseFailureCount: number;
  exhaustedRecordCount: number;
  cashAccountCount: number;
  earliestTransaction: string | null;
  warnings: string[];
}

export interface SourceHealth {
  provider: string;
  displayName: string | null;
  status: string;
  configured: boolean;
  lastSuccessAt: string | null;
  lastAttemptAt: string | null;
  lastError: string | null;
  consecutiveFailures: number;
  backfillComplete: boolean;
  backfillThrough: string | null;
  lastRunStatus: string | null;
  lastRunCreated: number | null;
  lastRunExamined: number | null;
}

export interface Transaction {
  id: string;
  amountMinor: number;
  currency: string;
  occurredOn: string;
  occurredAtTime: string | null;
  postedOn: string | null;
  type: string;
  typeLabel: string;
  status: string;
  merchant: string | null;
  merchantRaw: string | null;
  categoryId: string | null;
  categoryName: string | null;
  categoryPath: string | null;
  categorySource: string;
  categoryConfidence: number;
  accountId: string | null;
  accountName: string | null;
  counterAccountId: string | null;
  counterAccountName: string | null;
  description: string | null;
  notes: string | null;
  externalReference: string | null;
  refundOfId: string | null;
  reversesId: string | null;
  reconciliationStatus: string;
  origin: string;
  isSpending: boolean;
  isIncome: boolean;
  sourceCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface Account {
  id: string;
  name: string;
  type: string;
  currency: string;
  institution: string | null;
  instrumentHint: string | null;
  openingBalanceMinor: number;
  creditLimitMinor: number | null;
  statementDay: number | null;
  dueDay: number | null;
  archived: boolean;
  netMovementMinor: number;
  balanceMinor: number;
  balanceConfidence: string;
  isLiability: boolean;
  manualOnly: boolean;
}

export interface CreditCard {
  accountId: string;
  name: string;
  institution: string | null;
  instrumentHint: string | null;
  currency: string;
  outstandingMinor: number;
  creditLimitMinor: number | null;
  availableCreditMinor: number | null;
  utilisationPercent: number | null;
  currentCycleFrom: string | null;
  currentCycleTo: string | null;
  currentCycleDueOn: string | null;
  currentCycleSpendingMinor: number | null;
  previousCycleFrom: string | null;
  previousCycleTo: string | null;
  previousCycleSpendingMinor: number | null;
  cycleConfigured: boolean;
}

export interface Category {
  id: string;
  name: string;
  parentId: string | null;
  path: string;
  excludedFromSpending: boolean;
  system: boolean;
  icon: string | null;
  children: Category[];
}

export interface BudgetProgress {
  budgetId: string;
  categoryId: string | null;
  categoryName: string;
  budgetMinor: number;
  spentMinor: number;
  remainingMinor: number;
  usedPercent: number;
  warnAtPercent: number;
  warning: boolean;
  exceeded: boolean;
  currency: string;
  isOverall: boolean;
  isMonthOverride: boolean;
}

export interface ReviewItem {
  id: string;
  kind: string;
  status: string;
  summary: string;
  sourceRecordId: string | null;
  canonicalTransactionId: string | null;
  candidates: {
    transactionId: string;
    score: number;
    rationale: string;
    amountMinor: number;
    currency: string;
    occurredOn: string;
    merchant: string | null;
  }[];
  createdAt: string;
}

export interface DashboardResponse {
  from: string;
  to: string;
  rangeLabel: string;
  totals: Totals;
  byCategory: CategorySlice[];
  byMerchant: MerchantSlice[];
  byPaymentMethod: { accountId: string | null; accountName: string; accountType: string; totalMinor: number; transactionCount: number }[];
  monthlyTrend: MonthPoint[];
  accounts: Account[];
  creditCards: CreditCard[];
  recentTransactions: Transaction[];
  sources: SourceHealth[];
  coverage: Coverage;
  reviewCounts: Record<string, number>;
}

// ---------- HTTP client ----------------------------------------------------

const BASE = '/api';

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json', ...options?.headers },
    ...options,
  });
  if (!res.ok) {
    let detail = res.statusText;
    try {
      const body = await res.json();
      detail = body?.detail ?? body?.message ?? detail;
    } catch {/* ignore */}
    throw new Error(`${res.status}: ${detail}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}

// ---------- API calls ------------------------------------------------------

export const api = {
  // Dashboard
  dashboard: (range = 'this-month', trendMonths = 12) =>
    request<DashboardResponse>(`/dashboard?range=${range}&trendMonths=${trendMonths}`),

  totals: (range = 'this-month') =>
    request<Totals>(`/reports/totals?range=${range}`),

  trend: (months = 12) =>
    request<MonthPoint[]>(`/reports/trend?months=${months}`),

  coverage: (range = 'this-month') =>
    request<Coverage>(`/reports/coverage?range=${range}`),

  categories_report: (range = 'this-month') =>
    request<CategorySlice[]>(`/reports/categories?range=${range}`),

  // Transactions
  transactions: (params: Record<string, string | number | boolean | undefined> = {}) => {
    const qs = Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
      .join('&');
    return request<PageDto<Transaction>>(`/transactions${qs ? '?' + qs : ''}`);
  },

  transaction: (id: string) => request<{ transaction: Transaction; sources: unknown[]; decisions: unknown[]; relatedRefunds: Transaction[] }>(`/transactions/${id}`),

  createTransaction: (body: object) =>
    request<Transaction>('/transactions', { method: 'POST', body: JSON.stringify(body) }),

  updateTransaction: (id: string, body: object) =>
    request<Transaction>(`/transactions/${id}`, { method: 'PUT', body: JSON.stringify(body) }),

  recategorize: (id: string, categoryId: string, learn = false) =>
    request<Transaction>(`/transactions/${id}/category`, {
      method: 'POST',
      body: JSON.stringify({ categoryId, learn }),
    }),

  deleteTransaction: (id: string) =>
    request<void>(`/transactions/${id}`, { method: 'DELETE' }),

  // Accounts
  accounts: (includeArchived = false) =>
    request<Account[]>(`/accounts?includeArchived=${includeArchived}`),

  creditCards: () => request<CreditCard[]>('/accounts/credit-cards'),

  createAccount: (body: object) =>
    request<Account>('/accounts', { method: 'POST', body: JSON.stringify(body) }),

  updateAccount: (id: string, body: object) =>
    request<Account>(`/accounts/${id}`, { method: 'PUT', body: JSON.stringify(body) }),

  archiveAccount: (id: string) =>
    request<Account>(`/accounts/${id}`, { method: 'DELETE' }),

  // Categories
  categories: () => request<Category[]>('/categories'),
  categoriesFlat: () => request<Category[]>('/categories/flat'),

  // Sources
  sourceStatus: () => request<SourceHealth[]>('/sources'),
  gmailStatus: () => request<Record<string, unknown>>('/sources/gmail/status'),
  gmailAuthorize: () => request<{ url: string; state: string }>('/sources/gmail/authorize'),
  disconnectGmail: () => request<Record<string, unknown>>('/sources/gmail', { method: 'DELETE' }),
  manualSync: (provider: string) =>
    request<Record<string, unknown>>(`/sources/${provider}/sync`, { method: 'POST' }),
  recentRuns: (limit = 20) => request<unknown[]>(`/sources/runs?limit=${limit}`),

  // Review queue
  reviewQueue: (page = 0, kind?: string) =>
    request<PageDto<ReviewItem>>(`/sources/review?page=${page}${kind ? '&kind=' + kind : ''}`),
  reviewCounts: () => request<Record<string, number>>('/sources/review/counts'),
  resolveReview: (id: string, action: string, transactionId?: string, note?: string) =>
    request<ReviewItem>(`/sources/review/${id}/resolve`, {
      method: 'POST',
      body: JSON.stringify({ action, transactionId, note }),
    }),

  // Budgets
  budgets: (month?: string) =>
    request<BudgetProgress[]>(`/data/budgets${month ? '?month=' + month : ''}`),
  createBudget: (body: object) =>
    request<unknown>('/data/budgets', { method: 'POST', body: JSON.stringify(body) }),
  deleteBudget: (id: string) => request<void>(`/data/budgets/${id}`, { method: 'DELETE' }),

  // Data
  exportCsvUrl: (from?: string, to?: string) =>
    `/api/data/csv/export${from ? `?from=${from}&to=${to ?? ''}` : ''}`,
  backupUrl: () => '/api/data/backup',
};
