import { BrowserRouter, Routes, Route } from 'react-router-dom';
import Layout from './components/Layout';
import Dashboard from './pages/Dashboard';
import Transactions from './pages/Transactions';
import Accounts from './pages/Accounts';
import CreditCards from './pages/CreditCards';
import Budgets from './pages/Budgets';
import Reports from './pages/Reports';
import DataSources from './pages/DataSources';
import Settings from './pages/Settings';
import { EmptyState } from './components/Layout';
import { FileQuestion } from 'lucide-react';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index element={<Dashboard />} />
          <Route path="transactions" element={<Transactions />} />
          <Route path="transactions/:id" element={<TransactionDetail />} />
          <Route path="accounts" element={<Accounts />} />
          <Route path="cards" element={<CreditCards />} />
          <Route path="budgets" element={<Budgets />} />
          <Route path="reports" element={<Reports />} />
          <Route path="sources" element={<DataSources />} />
          <Route path="settings" element={<Settings />} />
          <Route path="*" element={
            <div className="flex items-center justify-center h-full">
              <EmptyState
                icon={FileQuestion}
                title="Page not found"
                body="The page you're looking for doesn't exist."
              />
            </div>
          } />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}

// Placeholder for transaction detail until full implementation
function TransactionDetail() {
  const id = window.location.pathname.split('/').pop();
  return (
    <div className="max-w-3xl mx-auto">
      <div className="p-4 rounded-lg mb-4" style={{ background: 'var(--surface-2)', border: '1px solid var(--border-default)' }}>
        <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>
          Transaction detail page (ID: {id}) — full evidence trail and edit controls available via API at <code>/api/transactions/{id}</code>
        </p>
      </div>
    </div>
  );
}
