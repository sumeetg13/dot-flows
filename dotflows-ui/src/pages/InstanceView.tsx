import { useMemo } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Background, Controls, ReactFlow } from '@xyflow/react'
import { api, formatTime, type InstanceDetail, type ProcessDefinition } from '../api'
import { decorateEdge, nodeTypes, toFlow, type NodeState } from '../flow'
import { Button, ErrorNote, StatusBadge } from '../components/ui'

function Diagram({ definition, detail }: { definition: ProcessDefinition; detail: InstanceDetail }) {
  const { nodes, edges } = useMemo(() => {
    const flow = toFlow(definition)
    const { instance, steps } = detail
    const state: Record<string, NodeState> = {}
    for (const step of steps) state[step.nodeId] = step.status === 'FAILED' ? 'failed' : 'done'
    if (instance.currentNodeId && instance.status === 'RUNNING') state[instance.currentNodeId] = 'current'
    if (instance.currentNodeId && instance.status === 'FAILED') state[instance.currentNodeId] = 'failed'

    // The path taken: consecutive distinct nodes in the step history, plus the hop to where it is now.
    const visited = steps.map((s) => s.nodeId)
    if (instance.currentNodeId) visited.push(instance.currentNodeId)
    const taken = new Set<string>()
    for (let i = 1; i < visited.length; i++) if (visited[i] !== visited[i - 1]) taken.add(`${visited[i - 1]}>${visited[i]}`)

    return {
      nodes: flow.nodes.map((n) => ({ ...n, data: { ...n.data, state: state[n.id] }, draggable: false, selectable: false })),
      edges: flow.edges.map((e) => decorateEdge({ ...e, selectable: false, data: { ...e.data, taken: taken.has(`${e.source}>${e.target}`) } })),
    }
  }, [definition, detail])

  return (
    <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} fitView fitViewOptions={{ maxZoom: 1, padding: 0.2 }} nodesConnectable={false} elementsSelectable={false}>
      <Background gap={20} color="#cbd5e1" />
      <Controls showInteractive={false} />
    </ReactFlow>
  )
}

export function InstanceView() {
  const { id = '' } = useParams()
  const queryClient = useQueryClient()
  const detail = useQuery({
    queryKey: ['instance', id],
    queryFn: () => api.instance(id),
    refetchInterval: (query) => (query.state.data?.instance.status === 'RUNNING' ? 1500 : false),
  })
  const instance = detail.data?.instance
  const definition = useQuery({
    queryKey: ['processes', instance?.processKey, 'version', instance?.version],
    queryFn: () => api.version<ProcessDefinition>('processes', instance!.processKey, instance!.version),
    enabled: !!instance,
    staleTime: Infinity,
  })

  const act = useMutation({
    mutationFn: (action: 'retry' | 'cancel') => (action === 'retry' ? api.retry(id) : api.cancel(id)),
    onSuccess: (data) => queryClient.setQueryData(['instance', id], data),
  })

  if (detail.error) {
    return (
      <div className="p-6">
        <ErrorNote error={detail.error} />
      </div>
    )
  }
  if (!detail.data || !instance) return <p className="p-6 text-sm text-slate-500">Loading…</p>

  return (
    <div className="flex h-full flex-col">
      <div className="flex shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-4 py-2">
        <Link to="/instances" className="text-sm text-slate-500 hover:text-slate-800">
          ← Instances
        </Link>
        <span className="font-mono text-sm font-semibold">{instance.id}</span>
        <StatusBadge status={instance.status} />
        <Link to={`/processes/${instance.processKey}`} className="text-sm text-indigo-700 hover:underline">
          {instance.processKey}
        </Link>
        <span className="text-sm text-slate-500">v{instance.version}</span>
        {instance.businessKey && <span className="text-sm text-slate-500">· {instance.businessKey}</span>}
        <div className="flex-1" />
        {instance.status === 'FAILED' && (
          <Button variant="primary" disabled={act.isPending} onClick={() => act.mutate('retry')} title={`Run "${instance.currentNodeId}" again with a fresh set of attempts`}>
            Retry from failed step
          </Button>
        )}
        {(instance.status === 'RUNNING' || instance.status === 'FAILED') && (
          <Button variant="danger" disabled={act.isPending} onClick={() => window.confirm('Cancel this instance? It cannot be resumed.') && act.mutate('cancel')}>
            Cancel instance
          </Button>
        )}
      </div>

      {(instance.error || act.error) && (
        <div className="shrink-0 space-y-2 border-b border-slate-200 bg-white px-4 py-3">
          {act.error && <ErrorNote error={act.error} />}
          {instance.error && (
            <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800">
              <span className="font-medium">{instance.status === 'FAILED' ? `Failed at "${instance.currentNodeId}": ` : 'Last error: '}</span>
              <span className="font-mono text-xs">{instance.error}</span>
            </div>
          )}
        </div>
      )}

      <div className="h-[42%] min-h-56 shrink-0 border-b border-slate-200 bg-slate-50">
        {definition.data ? <Diagram definition={definition.data.definition} detail={detail.data} /> : definition.error ? <ErrorNote error={definition.error} /> : null}
      </div>

      <div className="grid min-h-0 flex-1 grid-cols-3 gap-0">
        <div className="col-span-2 overflow-auto border-r border-slate-200 bg-white">
          <table className="w-full">
            <thead className="sticky top-0 bg-white">
              <tr>
                <th className="th">Step</th>
                <th className="th">Result</th>
                <th className="th">Attempt</th>
                <th className="th">Finished</th>
                <th className="th">Took</th>
                <th className="th">Output / error</th>
              </tr>
            </thead>
            <tbody>
              {detail.data.steps.map((step) => (
                <tr key={step.id} className="align-top">
                  <td className="td font-mono text-xs">{step.nodeId}</td>
                  <td className={`td ${step.status === 'FAILED' ? 'text-red-700' : 'text-emerald-700'}`}>{step.status.toLowerCase()}</td>
                  <td className="td text-slate-600">{step.nodeType === 'TASK' ? step.attempt : ''}</td>
                  <td className="td whitespace-nowrap text-slate-600">{new Date(step.endedAt).toLocaleTimeString()}</td>
                  <td className="td whitespace-nowrap text-slate-600">
                    {step.nodeType === 'TASK' ? `${new Date(step.endedAt).getTime() - new Date(step.startedAt).getTime()} ms` : ''}
                  </td>
                  <td className="td max-w-md break-words font-mono text-xs text-slate-700">
                    {step.error ?? (step.output && Object.keys(step.output).length ? JSON.stringify(step.output) : '')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="overflow-auto bg-white p-4">
          <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">Variables</h3>
          <pre className="whitespace-pre-wrap break-words font-mono text-xs text-slate-800">{JSON.stringify(instance.variables, null, 2)}</pre>
          <p className="hint mt-4">
            Started {formatTime(instance.startedAt)}
            {instance.endedAt ? `, ended ${formatTime(instance.endedAt)}` : ''}
          </p>
        </div>
      </div>
    </div>
  )
}
