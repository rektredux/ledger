# Ledger — Design System

## Principles

**Strictly monochrome.** The application uses black, white, and neutral gray.
Color is used only for unambiguous state communication:
- `var(--color-error)` / red → errors, overdraft, negative balance, parse failures
- `var(--color-warning)` / amber → payment due soon, approaching limit, backfill warning
- `var(--color-success)` / green → confirmation messages, surplus (badges only)

Financial direction is always communicated by **sign** (−/+), **icon AND label** — never
by color alone. A red number next to a green number without labels is inaccessible;
a − sign with an "expense" label is not.

**Typography-driven hierarchy.** Visual weight comes from font size, weight, and spacing —
not from colored headers or gradients. Sections are separated by borders and whitespace,
not by contrasting background fills.

**Tabular numerals everywhere monetary.** Use `font-variant-numeric: tabular-nums`
(`amount` class or `tabular` class) on every monetary amount, percentage, and count
that will be aligned in columns. If two numbers with different digit counts sit in the
same row, they must align right with tabular figures.

---

## Design tokens (CSS custom properties in `src/style.css`)

All tokens are defined as `--<name>` and applied via inline styles or Tailwind's
`style={{}}` prop. Do not hardcode hex values anywhere in components.

### Surface
| Token | Light | Dark | Use |
|---|---|---|---|
| `--surface-0` | `#ffffff` | `#0a0a0a` | Page background |
| `--surface-1` | `#fafafa` | `#111111` | Card / panel background |
| `--surface-2` | `#f5f5f5` | `#1a1a1a` | Hover fill, selected row, filter bar |
| `--surface-3` | `#e5e5e5` | `#262626` | Input background, code block |

### Border
| Token | Light | Dark |
|---|---|---|
| `--border-default` | `#e5e5e5` | `#262626` |
| `--border-strong` | `#d4d4d4` | `#333333` |
| `--border-focus` | `#171717` | `#e5e5e5` |

### Text
| Token | Light | Dark | Use |
|---|---|---|---|
| `--text-primary` | `#171717` | `#fafafa` | Page titles, card headings, amounts |
| `--text-secondary` | `#525252` | `#d4d4d4` | Body text, table cells |
| `--text-tertiary` | `#737373` | `#a3a3a3` | Nav items, secondary labels |
| `--text-muted` | `#a3a3a3` | `#737373` | Metadata, captions |
| `--text-disabled` | `#d4d4d4` | `#404040` | Placeholder, "—" dashes |
| `--text-inverse` | `#ffffff` | `#0a0a0a` | Text on dark/light button fills |

### Charts
| Token | Value | Use |
|---|---|---|
| `--chart-1` | `#171717` / `#fafafa` | Primary series |
| `--chart-2` | `#525252` / `#a3a3a3` | Secondary series |
| `--chart-3` | `#a3a3a3` / `#525252` | Tertiary series |
| `--chart-4` | `#d4d4d4` / `#333333` | Quaternary / light fill |
| `--chart-grid` | `#f5f5f5` / `#1a1a1a` | Grid lines |

The exported `CHART_COLORS` array from `Layout.tsx` is `[chart-1, chart-2, chart-3, chart-4]`.

---

## Typography

Typeface: **Inter** (regular text) + **Inter Tight** (headings, large amounts).
Both loaded from Google Fonts in `style.css`.

| Variant | Tag / Class | Size | Weight |
|---|---|---|---|
| Page title | `PageHeader title` | `text-lg` | 600 |
| Section heading | `SectionTitle` | `text-xs` uppercase tracking-widest | 600 |
| Card heading | `text-sm font-medium` | 14px | 500 |
| Body | `text-sm` | 14px | 400 |
| Metadata | `text-xs` | 12px | 400 |
| Large amount | `text-2xl amount` | 24px | 600, Inter Tight |
| Small amount | `text-sm amount` | 14px | 600 |

---

## Components (all in `src/components/Layout.tsx`)

### Primitive
- `Card`, `CardHeader`, `CardBody` — surface + border container
- `SectionTitle` — small-caps, muted, section label
- `Divider` — 1px horizontal border
- `Badge` — variants: `default | success | warning | error | info | muted`
- `Button` — variants: `primary | secondary | ghost | danger`
- `Input`, `Select` — form controls with label and error support
- `ProgressBar` — variants: `default | warning | error`

### Data display
- `StatTile` — KPI metric card with value, label, trend, sub-text
- `Amount` — monetary value with correct sign, tabular numerals, optional intent color
- `Table`, `Th`, `Td`, `Tr` — data table with hover state on Tr

### State
- `Spinner`, `PageLoader`, `Skeleton` — loading states
- `EmptyState` — no-data state with optional icon, body, action
- `ErrorBanner`, `WarningBanner`, `InfoBanner`, `SuccessBanner` — alerts

### Navigation
- `PageHeader` — title + subtitle + right-aligned actions
- `RangePicker` — date-range dropdown
- `RANGE_OPTIONS` — preset list

### Charts
- `CHART_COLORS` — ordered monochrome series array
- `CHART_TOOLTIP_STYLE` — shared Recharts tooltip contentStyle
- `AXIS_STYLE` — shared XAxis/YAxis tick props
- `GRID_STYLE` — shared CartesianGrid stroke props

---

## Chart guidelines

1. **Select by question.** Monthly trend → `AreaChart` or `BarChart`. Category comparison → horizontal bars (CSS, not Recharts). Composition → `PieChart` with `innerRadius` (donut). Budget progress → `ProgressBar`.
2. **No bright fills.** Use `CHART_COLORS[i % CHART_COLORS.length]`.
3. **Gridlines**: `CartesianGrid {...GRID_STYLE} vertical={false}` — horizontal only.
4. **Axes**: `tick={AXIS_STYLE} tickLine={false} axisLine={false}`.
5. **Tooltip**: `contentStyle={CHART_TOOLTIP_STYLE}`.
6. **Format monetary values** in tooltip `formatter={(v) => formatINR(v)}`.
7. **Height**: 200–280px in cards. Use `ResponsiveContainer width="100%"`.
8. **Empty state**: show `<EmptyState>` instead of an empty chart area.

---

## Accessibility checklist

- All interactive elements have visible focus styles (`:focus-visible` with `outline: 2px solid var(--border-focus)`).
- Financial direction is never communicated by color alone — always include sign character and label.
- Reduced motion: `@media (prefers-reduced-motion: reduce)` disables animations in `style.css`.
- Semantic HTML: use `<table>` for tabular data, `<nav>` for navigation, `<main>` for content.
- Color contrast: `--text-primary` on `--surface-0` is 16.6:1 (light) and 17.4:1 (dark) — far exceeds WCAG AA.

---

## What to avoid

- Bright accent colors (`bg-indigo-600`, `text-blue-500`, etc.)
- Gradient backgrounds (`bg-gradient-*`)
- Glassmorphism (`backdrop-blur`)
- Heavy box shadows (`shadow-xl`, custom `rgba(...)`)
- Pill-shaped components (`rounded-full` on cards or buttons)
- Hardcoded hex values in component files
- Duplicating chart theme configuration
