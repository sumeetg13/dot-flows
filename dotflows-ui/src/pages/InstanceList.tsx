import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, formatTime } from '../api'
import { Empty, ErrorNote, Page, StatusBadge } from '../components/ui'

export function InstanceList() {
  const [processKey, setProcessKey] = useState('')
  const [status, setStatus] = useState('')
  const [businessKey, setBusinessKey] = useState('')
  const processes = useQuery({ queryKey: ['processes'], queryFn: () => api.list('processes') })
  const instances = useQuery({
    queryKey: ['instances', processKey, status, businessKey],
    queryFn: () => api.instances({ processKey, status, businessKey }),
    refetchInterval: 3000,
  })

  return (
    <Page title="Instances">
      <div className="mb-4 flex gap-3">
        <select className="field !w-56" value={processKey} onChange={(e) => setProcessKey(e.target.value)}>
          <option value="">All processes</option>
          {processes.data?.map((p) => (
            <option key={p.key} value={p.key}>
              {p.key}
            </option>
          ))}
        </select>
        <select className="field !w-44" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">Any status</option>
          <option value="RUNNING">Running</option>
          <option value="FAILED">Failed</option>
          <option value="COMPLETED">Completed</option>
          <option value="CANCELLED">Cancelled</option>
        </select>
        <input className="field !w-64" value={businessKey} onChange={(e) => setBusinessKey(e.target.value.trim())} placeholder="Business key (exact)" />
      </div>
      <ErrorNote error={instances.error} />
      {instances.data?.length === 0 && <Empty>No instances match. Start one from a process's editor or with FlowEngine.start(…).</Empty>}
      {!!instances.data?.length && (
        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <table className="w-full">
            <thead>
              <tr>
                <th className="th">Instance</th>
                <th className="th">Process</th>
                <th className="th">Business key</th>
                <th className="th">Status</th>
                <th className="th">At</th>
                <th className="th">Started</th>
                <th className="th">Ended</th>
              </tr>
            </thead>
            <tbody>
              {instances.data.map((i) => (
                <tr key={i.id} className="hover:bg-slate-50">
                  <td className="td">
                    <Link to={`/instances/${i.id}`} className="font-mono text-indigo-700 hover:underline">
                      {i.id.slice(0, 8)}
                    </Link>
                  </td>
                  <td className="td">
                    <span className="font-mono">{i.processKey}</span> <span className="text-slate-400">v{i.version}</span>
                  </td>
                  <td className="td text-slate-600">{i.businessKey ?? '—'}</td>
                  <td className="td">
                    <StatusBadge status={i.status} />
                  </td>
                  <td className="td font-mono text-xs text-slate-600">{i.status === 'COMPLETED' ? '' : i.currentNodeId}</td>
                  <td className="td text-slate-600">{formatTime(i.startedAt)}</td>
                  <td className="td text-slate-600">{formatTime(i.endedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {instances.data?.length === 50 && <p className="hint mt-2">Showing the 50 most recent. Narrow the filters to find older instances.</p>}
    </Page>
  )
}
