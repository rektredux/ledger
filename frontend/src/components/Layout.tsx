/**
 * Application shell: sidebar navigation + responsive top-bar.
 * Also exports all shared UI primitives so pages import from one place.
 *
 * Design: strictly monochrome.  No bright accents — hierarchy is established
 * through typography, weight, size, and subtle borders.
 */
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import {
  LayoutDashboard, ArrowLeftRight, Wallet, CreditCard, Target,
  BarChart3, Database, Settings, Menu, X, AlertTriangle, Sun, Moon,
  CircleAlert, Info, CheckCircle2, Loader2
} from 'lucide-react';
import { useState, useEffect, ReactNode } from 'react';

// ── Navigation definition ───────────────────────────────────────────────
const NAV = [
  { to: '/',              label: 'Dashboard',    icon: LayoutDashboard },
  { to: '/transactions',  label: 'Transactions', icon: ArrowLeftRight },
  { to: '/accounts',      label: 'Accounts',     icon: Wallet },
  { to: '/cards',         label: 'Credit Cards', icon: CreditCard },
  { to: '/budgets',       label: 'Budgets',      icon: Target },
  { to: '/reports',       label: 'Reports',      icon: BarChart3 },
  { to: '/sources',       label: 'Data Sources', icon: Database },
  { to: '/settings',      label: 'Settings',     icon: Settings },
];

// ── Dark-mode hook ───────────────────────────────────────────────────────
function useDark() {
  const [dark, setDark] = useState(() =>
    typeof window !== 'undefined' &&
    (localStorage.getItem('theme') === 'dark' ||
     (!localStorage.getItem('theme') &&
      window.matchMedia('(prefers-color-scheme: dark)').matches))
  );

  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
    localStorage.setItem('theme', dark ? 'dark' : 'light');
  }, [dark]);

  return [dark, setDark] as const;
}

// ── Root layout ──────────────────────────────────────────────────────────
export default function Layout() {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [dark, setDark] = useDark();
  const location = useLocation();

  useEffect(() => setSidebarOpen(false), [location.pathname]);

  return (
    <div className="min-h-screen flex" style={{ background: 'var(--surface-0)', color: 'var(--text-primary)' }}>
      {/* Mobile backdrop */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 z-20 lg:hidden"
          style={{ background: 'rgba(0,0,0,0.5)' }}
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* Sidebar */}
      <aside className={`
        fixed inset-y-0 left-0 z-30 w-60 flex flex-col
        lg:relative lg:translate-x-0
        transform transition-transform duration-200
        ${sidebarOpen ? 'translate-x-0' : '-translate-x-full'}
      `}
        style={{ background: 'var(--surface-0)', borderRight: '1px solid var(--border-default)' }}>

        {/* Brand */}
        <div className="flex items-center justify-between h-14 px-5"
          style={{ borderBottom: '1px solid var(--border-default)' }}>
          <span className="text-sm font-semibold tracking-tight" style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-tight)' }}>
            Ledger
          </span>
          <button onClick={() => setSidebarOpen(false)} className="lg:hidden p-1 rounded"
            style={{ color: 'var(--text-muted)' }}>
            <X size={16} />
          </button>
        </div>

        {/* Nav links */}
        <nav className="flex-1 overflow-y-auto py-3 px-2">
          {NAV.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/'}
              className={({ isActive }) => `
                flex items-center gap-2.5 px-3 py-2 rounded text-sm mb-0.5
                transition-colors focus-visible:outline
                ${isActive ? 'active-nav' : 'inactive-nav'}
              `}
              style={({ isActive }) => ({
                background: isActive ? 'var(--surface-2)' : 'transparent',
                color: isActive ? 'var(--text-primary)' : 'var(--text-tertiary)',
                fontWeight: isActive ? '500' : '400',
              })}
            >
              <Icon size={16} />
              {label}
            </NavLink>
          ))}
        </nav>

        {/* Sidebar footer */}
        <div className="px-2 py-3" style={{ borderTop: '1px solid var(--border-default)' }}>
          <button
            onClick={() => setDark(!dark)}
            className="flex items-center gap-2 w-full px-3 py-2 rounded text-sm"
            style={{ color: 'var(--text-muted)' }}
          >
            {dark ? <Sun size={14} /> : <Moon size={14} />}
            {dark ? 'Light mode' : 'Dark mode'}
          </button>
          <p className="px-3 mt-2 text-xs" style={{ color: 'var(--text-disabled)' }}>
            Local — no cloud
          </p>
        </div>
      </aside>

      {/* Content */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Mobile top bar */}
        <header className="lg:hidden flex items-center justify-between h-12 px-4"
          style={{ background: 'var(--surface-0)', borderBottom: '1px solid var(--border-default)' }}>
          <button onClick={() => setSidebarOpen(true)} className="p-1" style={{ color: 'var(--text-tertiary)' }}>
            <Menu size={20} />
          </button>
          <span className="text-sm font-semibold" style={{ fontFamily: 'var(--font-tight)', color: 'var(--text-primary)' }}>
            Ledger
          </span>
          <button onClick={() => setDark(!dark)} className="p-1" style={{ color: 'var(--text-tertiary)' }}>
            {dark ? <Sun size={16} /> : <Moon size={16} />}
          </button>
        </header>

        <main className="flex-1 overflow-auto p-4 sm:p-6 lg:p-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════════════════════
// Shared UI primitives — all monochrome
// ═══════════════════════════════════════════════════════════════════════════

interface Children { children: ReactNode; className?: string; style?: React.CSSProperties }

// ── Card ─────────────────────────────────────────────────────────────────
export function Card({ children, className = '', style }: Children) {
  return (
    <div
      className={`rounded-lg ${className}`}
      style={{
        background: 'var(--surface-1)',
        border: '1px solid var(--border-default)',
        ...style,
      }}
    >
      {children}
    </div>
  );
}

export function CardHeader({ children, className = '' }: Children) {
  return (
    <div className={`px-5 py-3.5 ${className}`}
      style={{ borderBottom: '1px solid var(--border-default)' }}>
      {children}
    </div>
  );
}

export function CardBody({ children, className = '' }: Children) {
  return <div className={`p-5 ${className}`}>{children}</div>;
}

export function SectionTitle({ children }: { children: ReactNode }) {
  return (
    <h2 className="text-xs font-semibold uppercase tracking-widest"
      style={{ color: 'var(--text-muted)', letterSpacing: '0.08em' }}>
      {children}
    </h2>
  );
}

// ── KPI / Stat tile ───────────────────────────────────────────────────────
interface StatProps {
  label: string;
  value: string;
  sub?: string;
  trend?: number | null;
  caption?: string;
  intent?: 'neutral' | 'negative' | 'positive';
}

export function StatTile({ label, value, sub, trend, caption, intent = 'neutral' }: StatProps) {
  const valueColor = intent === 'negative'
    ? 'var(--color-error)'
    : intent === 'positive'
    ? 'var(--color-success)'
    : 'var(--text-primary)';

  return (
    <Card className="p-5 flex flex-col gap-1">
      <p className="text-xs font-medium" style={{ color: 'var(--text-tertiary)' }}>{label}</p>
      <p
        className="text-2xl font-semibold tracking-tight amount"
        style={{ color: valueColor, fontFamily: 'var(--font-tight)' }}
      >
        {value}
      </p>
      {sub && <p className="text-xs" style={{ color: 'var(--text-muted)' }}>{sub}</p>}
      {trend != null && (
        <p className="text-xs" style={{ color: trend >= 0 ? 'var(--color-error)' : 'var(--color-success)' }}>
          {trend >= 0 ? '↑' : '↓'} {Math.abs(trend)}% vs prior period
        </p>
      )}
      {caption && <p className="text-xs" style={{ color: 'var(--text-disabled)' }}>{caption}</p>}
    </Card>
  );
}

// ── Badge ─────────────────────────────────────────────────────────────────
type BadgeVariant = 'default' | 'success' | 'warning' | 'error' | 'info' | 'muted';

const BADGE_STYLES: Record<BadgeVariant, React.CSSProperties> = {
  default: { background: 'var(--surface-3)', color: 'var(--text-secondary)' },
  success: { background: 'var(--color-success-bg)', color: 'var(--color-success)', border: '1px solid var(--color-success-border)' },
  warning: { background: 'var(--color-warning-bg)', color: 'var(--color-warning)', border: '1px solid var(--color-warning-border)' },
  error:   { background: 'var(--color-error-bg)', color: 'var(--color-error)', border: '1px solid var(--color-error-border)' },
  info:    { background: 'var(--surface-2)', color: 'var(--text-secondary)', border: '1px solid var(--border-default)' },
  muted:   { background: 'transparent', color: 'var(--text-muted)', border: '1px solid var(--border-default)' },
};

export function Badge({ children, variant = 'default' }: { children: ReactNode; variant?: BadgeVariant }) {
  return (
    <span
      className="inline-flex items-center px-1.5 py-0.5 rounded text-xs font-medium"
      style={{ borderRadius: 'var(--radius-sm)', ...BADGE_STYLES[variant] }}
    >
      {children}
    </span>
  );
}

// ── Button ────────────────────────────────────────────────────────────────
interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  loading?: boolean;
}

const BTN_VARIANT: Record<string, React.CSSProperties> = {
  primary: {
    background: 'var(--text-primary)',
    color: 'var(--surface-0)',
    border: '1px solid var(--text-primary)',
  },
  secondary: {
    background: 'transparent',
    color: 'var(--text-primary)',
    border: '1px solid var(--border-strong)',
  },
  ghost: {
    background: 'transparent',
    color: 'var(--text-secondary)',
    border: 'none',
  },
  danger: {
    background: 'var(--color-error-bg)',
    color: 'var(--color-error)',
    border: '1px solid var(--color-error-border)',
  },
};

const BTN_SIZE: Record<string, string> = {
  sm: 'px-3 py-1.5 text-xs',
  md: 'px-4 py-2 text-sm',
  lg: 'px-5 py-2.5 text-sm',
};

export function Button({
  children, variant = 'secondary', size = 'md',
  loading, className = '', disabled, ...props
}: ButtonProps) {
  return (
    <button
      className={`inline-flex items-center gap-2 rounded font-medium transition-opacity
        disabled:opacity-40 disabled:cursor-not-allowed ${BTN_SIZE[size]} ${className}`}
      style={{ borderRadius: 'var(--radius-md)', ...BTN_VARIANT[variant] }}
      disabled={disabled || loading}
      {...props}
    >
      {loading && <Loader2 size={14} className="animate-spin" />}
      {children}
    </button>
  );
}

// ── Input ─────────────────────────────────────────────────────────────────
interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
}

export function Input({ label, error, className = '', ...props }: InputProps) {
  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label className="text-xs font-medium" style={{ color: 'var(--text-secondary)' }}>
          {label}
        </label>
      )}
      <input
        className={`px-3 py-2 text-sm rounded transition-colors
          focus:outline-none focus-visible:ring-1 ${className}`}
        style={{
          background: 'var(--surface-0)',
          border: `1px solid ${error ? 'var(--color-error)' : 'var(--border-default)'}`,
          color: 'var(--text-primary)',
          borderRadius: 'var(--radius-md)',
        }}
        {...props}
      />
      {error && (
        <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>
      )}
    </div>
  );
}

// ── Select ────────────────────────────────────────────────────────────────
interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  label?: string;
}
export function Select({ label, children, className = '', ...props }: SelectProps) {
  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label className="text-xs font-medium" style={{ color: 'var(--text-secondary)' }}>
          {label}
        </label>
      )}
      <select
        className={`px-3 py-2 text-sm rounded appearance-none ${className}`}
        style={{
          background: 'var(--surface-0)',
          border: '1px solid var(--border-default)',
          color: 'var(--text-primary)',
          borderRadius: 'var(--radius-md)',
        }}
        {...props}
      >
        {children}
      </select>
    </div>
  );
}

// ── Loading skeleton ──────────────────────────────────────────────────────
export function Skeleton({ className = '' }: { className?: string }) {
  return (
    <div
      className={`rounded animate-pulse ${className}`}
      style={{ background: 'var(--surface-2)' }}
    />
  );
}

export function Spinner({ size = 'md' }: { size?: 'sm' | 'md' | 'lg' }) {
  const sz = size === 'sm' ? 16 : size === 'lg' ? 40 : 24;
  return (
    <div className="flex items-center justify-center py-10">
      <Loader2 size={sz} className="animate-spin" style={{ color: 'var(--text-muted)' }} />
    </div>
  );
}

export function PageLoader() {
  return (
    <div className="space-y-4">
      <Skeleton className="h-8 w-48" />
      <div className="grid grid-cols-4 gap-4">
        {[...Array(4)].map((_, i) => <Skeleton key={i} className="h-24" />)}
      </div>
      <Skeleton className="h-64" />
    </div>
  );
}

// ── Empty & error states ──────────────────────────────────────────────────
export function EmptyState({
  icon: Icon, title, body, action
}: {
  icon?: React.ComponentType<{ size: number; style?: React.CSSProperties }>;
  title: string;
  body?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center py-16 text-center">
      {Icon && <Icon size={40} style={{ color: 'var(--text-disabled)', marginBottom: 16 }} />}
      <p className="text-sm font-medium" style={{ color: 'var(--text-secondary)' }}>{title}</p>
      {body && <p className="mt-1 text-xs max-w-xs" style={{ color: 'var(--text-muted)' }}>{body}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function ErrorBanner({ message, onDismiss }: { message: string; onDismiss?: () => void }) {
  return (
    <div className="flex items-start gap-3 px-4 py-3 rounded-lg"
      style={{ background: 'var(--color-error-bg)', border: '1px solid var(--color-error-border)', borderRadius: 'var(--radius-md)' }}>
      <CircleAlert size={16} style={{ color: 'var(--color-error)', flexShrink: 0, marginTop: 2 }} />
      <p className="text-sm flex-1" style={{ color: 'var(--color-error)' }}>{message}</p>
      {onDismiss && (
        <button onClick={onDismiss} className="text-xs" style={{ color: 'var(--color-error)' }}>✕</button>
      )}
    </div>
  );
}

export function InfoBanner({ message }: { message: string }) {
  return (
    <div className="flex items-start gap-3 px-4 py-3 rounded-lg"
      style={{ background: 'var(--surface-2)', border: '1px solid var(--border-default)', borderRadius: 'var(--radius-md)' }}>
      <Info size={16} style={{ color: 'var(--text-tertiary)', flexShrink: 0, marginTop: 2 }} />
      <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>{message}</p>
    </div>
  );
}

export function WarningBanner({ message }: { message: string }) {
  return (
    <div className="flex items-start gap-3 px-4 py-3 rounded-lg"
      style={{ background: 'var(--color-warning-bg)', border: '1px solid var(--color-warning-border)', borderRadius: 'var(--radius-md)' }}>
      <AlertTriangle size={16} style={{ color: 'var(--color-warning)', flexShrink: 0, marginTop: 2 }} />
      <p className="text-sm" style={{ color: 'var(--color-warning)' }}>{message}</p>
    </div>
  );
}

export function SuccessBanner({ message }: { message: string }) {
  return (
    <div className="flex items-start gap-3 px-4 py-3 rounded-lg"
      style={{ background: 'var(--color-success-bg)', border: '1px solid var(--color-success-border)', borderRadius: 'var(--radius-md)' }}>
      <CheckCircle2 size={16} style={{ color: 'var(--color-success)', flexShrink: 0, marginTop: 2 }} />
      <p className="text-sm" style={{ color: 'var(--color-success)' }}>{message}</p>
    </div>
  );
}

// ── Page header ───────────────────────────────────────────────────────────
export function PageHeader({
  title, subtitle, actions
}: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <div className="flex flex-col sm:flex-row sm:items-start justify-between gap-3 mb-6">
      <div>
        <h1 className="text-lg font-semibold" style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-tight)' }}>
          {title}
        </h1>
        {subtitle && (
          <p className="text-xs mt-0.5 max-w-lg" style={{ color: 'var(--text-muted)' }}>{subtitle}</p>
        )}
      </div>
      {actions && <div className="flex items-center gap-2 flex-shrink-0">{actions}</div>}
    </div>
  );
}

// ── Table ─────────────────────────────────────────────────────────────────
export function Table({ children, className = '' }: Children) {
  return (
    <div className={`overflow-x-auto ${className}`}>
      <table className="w-full text-sm" style={{ borderCollapse: 'collapse' }}>
        {children}
      </table>
    </div>
  );
}
export function Th({ children, className = '', right = false }: Children & { right?: boolean }) {
  return (
    <th className={`px-4 py-2.5 text-left text-xs font-medium whitespace-nowrap ${right ? 'text-right' : ''} ${className}`}
      style={{ color: 'var(--text-muted)', borderBottom: '1px solid var(--border-default)', background: 'var(--surface-1)' }}>
      {children}
    </th>
  );
}
export function Td({ children, className = '', right = false }: Children & { right?: boolean }) {
  return (
    <td className={`px-4 py-3 ${right ? 'text-right' : ''} ${className}`}
      style={{ borderBottom: '1px solid var(--border-default)', color: 'var(--text-primary)' }}>
      {children}
    </td>
  );
}
export function Tr({ children, onClick, className = '' }: Children & { onClick?: () => void }) {
  return (
    <tr
      className={`${onClick ? 'cursor-pointer' : ''} ${className}`}
      onClick={onClick}
      style={onClick ? {} : {}}
      onMouseEnter={onClick ? (e) => { (e.currentTarget as HTMLElement).style.background = 'var(--surface-2)'; } : undefined}
      onMouseLeave={onClick ? (e) => { (e.currentTarget as HTMLElement).style.background = ''; } : undefined}
    >
      {children}
    </tr>
  );
}

// ── Progress bar ──────────────────────────────────────────────────────────
export function ProgressBar({
  value, max, variant = 'default', className = ''
}: { value: number; max: number; variant?: 'default' | 'warning' | 'error'; className?: string }) {
  const pct = max > 0 ? Math.min(100, (value / max) * 100) : 0;
  const barColor = variant === 'error' ? 'var(--color-error)' :
                   variant === 'warning' ? 'var(--color-warning)' :
                   'var(--text-primary)';
  return (
    <div className={`h-1.5 rounded-full overflow-hidden ${className}`}
      style={{ background: 'var(--surface-3)' }}>
      <div
        className="h-full rounded-full transition-all duration-500"
        style={{ width: `${pct}%`, background: barColor }}
      />
    </div>
  );
}

// ── Divider ───────────────────────────────────────────────────────────────
export function Divider() {
  return <hr style={{ border: 'none', borderTop: '1px solid var(--border-default)', margin: '0' }} />;
}

// ── Amount display ────────────────────────────────────────────────────────
/** Renders a monetary value with correct sign and optional direction colour. */
export function Amount({
  minor, showSign = false, intent, className = ''
}: { minor: number | null; showSign?: boolean; intent?: 'spending' | 'income' | 'neutral'; className?: string }) {
  if (minor == null) return <span style={{ color: 'var(--text-disabled)' }}>—</span>;
  const formatted = formatINR(minor);
  const color = intent === 'spending' ? 'var(--color-error)' :
                intent === 'income'   ? 'var(--color-success)' :
                'inherit';
  const prefix = showSign && minor > 0 ? '+' : showSign && minor < 0 ? '' : '';
  return (
    <span className={`amount ${className}`} style={{ color }}>
      {prefix}{formatted}
    </span>
  );
}

function formatINR(minor: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 2,
  }).format(minor / 100);
}

// ── Date range picker (simple) ────────────────────────────────────────────
export const RANGE_OPTIONS = [
  { value: 'this-month',  label: 'This month' },
  { value: 'last-month',  label: 'Last month' },
  { value: 'last-30-days', label: 'Last 30 days' },
  { value: 'last-90-days', label: 'Last 90 days' },
  { value: 'this-year',   label: 'This year' },
];

export function RangePicker({
  value, onChange
}: { value: string; onChange: (v: string) => void }) {
  return (
    <select
      value={value}
      onChange={e => onChange(e.target.value)}
      className="text-xs px-3 py-1.5 rounded"
      style={{
        background: 'var(--surface-0)',
        border: '1px solid var(--border-default)',
        color: 'var(--text-secondary)',
        borderRadius: 'var(--radius-md)',
      }}
    >
      {RANGE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
    </select>
  );
}

// ── Monochrome Recharts theme ─────────────────────────────────────────────
/**
 * Shared theme props for all Recharts components.
 * Import and spread into each chart: <BarChart {...CHART_DEFAULTS}>
 */
export const CHART_COLORS = ['#171717', '#525252', '#a3a3a3', '#d4d4d4'];

export const CHART_TOOLTIP_STYLE: React.CSSProperties = {
  background: 'var(--surface-1)',
  border: '1px solid var(--border-default)',
  borderRadius: 'var(--radius-sm)',
  fontSize: 12,
  color: 'var(--text-primary)',
  boxShadow: 'var(--shadow-md)',
};

export const AXIS_STYLE = {
  fontSize: 11,
  fill: 'var(--text-muted)',
  fontFamily: 'var(--font-sans)',
};

export const GRID_STYLE = {
  stroke: 'var(--chart-grid)',
  strokeDasharray: '3 3',
};
