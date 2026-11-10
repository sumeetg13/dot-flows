import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { HashRouter, NavLink, Navigate, Route, Routes } from 'react-router-dom'
import './index.css'
import { DefinitionList } from './pages/DefinitionList'
import { ProcessEditor } from './pages/ProcessEditor'
import { DecisionEditor } from './pages/DecisionEditor'
import { InstanceList } from './pages/InstanceList'
import { InstanceView } from './pages/InstanceView'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})

function Nav() {
  const link = ({ isActive }: { isActive: boolean }) =>
    `rounded-md px-3 py-1.5 text-sm font-medium ${isActive ? 'bg-slate-800 text-white' : 'text-slate-300 hover:text-white'}`
  return (
    <header className="flex h-12 shrink-0 items-center gap-6 bg-slate-900 px-5">
      <span className="text-sm font-semibold tracking-tight text-white">
        <span className="text-indigo-400">●</span> dot-flows
      </span>
      <nav className="flex gap-1">
        <NavLink to="/processes" className={link}>
          Processes
        </NavLink>
        <NavLink to="/decisions" className={link}>
          Decisions
        </NavLink>
        <NavLink to="/instances" className={link}>
          Instances
        </NavLink>
      </nav>
    </header>
  )
}

// A hash router keeps every route on the one index.html the host application serves.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <HashRouter>
        <div className="flex h-full flex-col">
          <Nav />
          <main className="min-h-0 flex-1 overflow-auto">
            <Routes>
              <Route path="/" element={<Navigate to="/processes" replace />} />
              <Route path="/processes" element={<DefinitionList kind="processes" />} />
              <Route path="/processes/:key" element={<ProcessEditor />} />
              <Route path="/decisions" element={<DefinitionList kind="decisions" />} />
              <Route path="/decisions/:key" element={<DecisionEditor />} />
              <Route path="/instances" element={<InstanceList />} />
              <Route path="/instances/:id" element={<InstanceView />} />
            </Routes>
          </main>
        </div>
      </HashRouter>
    </QueryClientProvider>
  </StrictMode>,
)
