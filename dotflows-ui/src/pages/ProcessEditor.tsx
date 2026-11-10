import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Background,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow,
  type Connection,
} from '@xyflow/react'
import { api, ApiError, type FlowNode, type NodeType, type ProcessDefinition, type ValidationError } from '../api'
import { decorateEdge, fromFlow, newNodeId, nodeTypes, toFlow, type EdgeData, type RFEdge, type RFNode } from '../flow'
import { EdgePanel, NodePanel } from '../components/PropertyPanel'
import { VersionsModal } from '../components/Versions'
import { Badge, Button, ErrorNote, Modal } from '../components/ui'

const PALETTE: { type: NodeType; label: string; hint: string }[] = [
  { type: 'task', label: 'Task', hint: 'Run a delegate, call an API or evaluate a decision' },
  { type: 'exclusiveGateway', label: 'Gateway', hint: 'Branch on conditions' },
  { type: 'end', label: 'End', hint: 'Finish the process' },
  { type: 'start', label: 'Start', hint: 'Where the process begins (exactly one)' },
]

function StartDialog({ processKey, onClose }: { processKey: string; onClose: () => void }) {
  const [variables, setVariables] = useState('{\n  \n}')
  const [businessKey, setBusinessKey] = useState('')
  const start = useMutation({
    mutationFn: () => {
      let parsed: unknown
      try {
        parsed = JSON.parse(variables.trim() || '{}')
      } catch (e) {
        throw new Error('Variables are not valid JSON: ' + (e as Error).message)
      }
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('Variables must be a JSON object, like {"orderId": 42}')
      }
      return api.start(processKey, parsed as Record<string, unknown>, businessKey || undefined)
    },
  })
  return (
    <Modal
      title={`Start an instance of ${processKey}`}
      onClose={onClose}
      footer={
        start.data ? (
          <Button onClick={onClose}>Close</Button>
        ) : (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button variant="primary" disabled={start.isPending} onClick={() => start.mutate()}>
              Start
            </Button>
          </>
        )
      }
    >
      {start.data ? (
        <p className="text-sm">
          Started.{' '}
          <Link className="font-medium text-indigo-700 hover:underline" to={`/instances/${start.data.instanceId}`}>
            Watch instance {start.data.instanceId.slice(0, 8)}…
          </Link>
        </p>
      ) : (
        <div className="space-y-4">
          <p className="text-sm text-slate-600">Runs the live version, not the draft on the canvas.</p>
          <div>
            <label className="label">Variables (JSON)</label>
            <textarea className="field field-mono" rows={8} value={variables} onChange={(e) => setVariables(e.target.value)} />
          </div>
          <div>
            <label className="label">Business key</label>
            <input className="field" value={businessKey} onChange={(e) => setBusinessKey(e.target.value)} placeholder="Optional reference of your own, e.g. an order number" />
          </div>
          <ErrorNote error={start.error} />
        </div>
      )}
    </Modal>
  )
}

function Editor({ processKey, initial }: { processKey: string; initial: ProcessDefinition }) {
  const queryClient = useQueryClient()
  const flow = useReactFlow<RFNode, RFEdge>()
  const canvas = useRef<HTMLDivElement>(null)

  const first = useMemo(() => toFlow(initial), [initial])
  const [nodes, setNodes, onNodesChange] = useNodesState<RFNode>(first.nodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState<RFEdge>(first.edges)
  const [name, setName] = useState(initial.name ?? '')
  const [saved, setSaved] = useState(() => JSON.stringify(fromFlow(processKey, initial.name, first.nodes, first.edges)))
  const [problems, setProblems] = useState<ValidationError[] | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [dialog, setDialog] = useState<'versions' | 'start' | null>(null)

  const definition = useMemo(() => fromFlow(processKey, name, nodes, edges), [processKey, name, nodes, edges])
  const dirty = JSON.stringify(definition) !== saved

  const versions = useQuery({ queryKey: ['processes', processKey, 'versions'], queryFn: () => api.versions('processes', processKey) })
  const live = versions.data?.find((v) => v.active)

  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => dirty && e.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const save = useMutation({
    mutationFn: () => api.saveDraft('processes', processKey, definition),
    onSuccess: () => {
      setSaved(JSON.stringify(definition))
      queryClient.invalidateQueries({ queryKey: ['processes'] })
    },
  })
  const validate = useMutation({
    mutationFn: () => api.validate('processes', definition),
    onSuccess: (result) => {
      setProblems(result.errors)
      setNotice(result.errors.length ? null : 'No problems found.')
    },
  })
  const deploy = useMutation({
    mutationFn: async () => {
      await save.mutateAsync()
      return api.deploy<ProcessDefinition>('processes', processKey)
    },
    onSuccess: (deployed) => {
      setProblems([])
      setNotice(`Deployed as version ${deployed.version}. New instances now start on it.`)
      queryClient.invalidateQueries({ queryKey: ['processes'] })
    },
    onError: (error) => {
      if (error instanceof ApiError && error.errors.length) setProblems(error.errors)
    },
  })

  // ---- canvas editing

  const addNode = useCallback(
    (type: NodeType, at?: { x: number; y: number }) => {
      const box = canvas.current?.getBoundingClientRect()
      const position = flow.screenToFlowPosition(at ?? { x: (box?.left ?? 0) + (box?.width ?? 600) / 2, y: (box?.top ?? 0) + (box?.height ?? 400) / 2 })
      setNodes((current) => {
        // Step aside until the new node is not sitting on top of an existing one.
        while (current.some((n) => Math.abs(n.position.x - position.x) < 60 && Math.abs(n.position.y - position.y) < 40)) {
          position.x += 30
          position.y += 70
        }
        const id = newNodeId(type, current.map((n) => n.id))
        const node: FlowNode = type === 'task' ? { id, type, taskType: 'delegate', config: {} } : { id, type }
        return [...current.map((n) => ({ ...n, selected: false })), { id, type, position, data: { node }, selected: true }]
      })
    },
    [flow, setNodes],
  )

  const onDrop = (event: DragEvent) => {
    const type = event.dataTransfer.getData('application/dotflows') as NodeType
    if (type) {
      event.preventDefault()
      addNode(type, { x: event.clientX, y: event.clientY })
    }
  }

  const onConnect = useCallback(
    (connection: Connection) => {
      if (connection.source === connection.target) return
      setEdges((current) => {
        if (current.some((e) => e.source === connection.source && e.target === connection.target)) return current
        const sourceIsGateway = nodes.find((n) => n.id === connection.source)?.type === 'exclusiveGateway'
        const firstFromGateway = sourceIsGateway && !current.some((e) => e.source === connection.source)
        let id = `e_${connection.source}_${connection.target}`
        for (let n = 2; current.some((e) => e.id === id); n++) id = `e_${connection.source}_${connection.target}_${n}`
        return [...current, { id, source: connection.source, target: connection.target, data: { isDefault: firstFromGateway } }]
      })
    },
    [nodes, setEdges],
  )

  const updateNode = (next: FlowNode) =>
    setNodes((current) => current.map((n) => (n.id === next.id ? { ...n, data: { ...n.data, node: next } } : n)))

  const updateEdge = (id: string, data: EdgeData) =>
    setEdges((current) => {
      const target = current.find((e) => e.id === id)
      return current.map((e) => {
        if (e.id === id) return { ...e, data }
        // A gateway has one default: choosing a new one clears the old.
        if (data.isDefault && target && e.source === target.source && e.data?.isDefault) return { ...e, data: { ...e.data, isDefault: false } }
        return e
      })
    })

  const load = (restored: ProcessDefinition) => {
    const next = toFlow({ ...restored, processKey })
    setNodes(next.nodes)
    setEdges(next.edges)
    setName(restored.name ?? '')
    setProblems(null)
    setNotice('Loaded into the editor. Save or deploy to keep it.')
  }

  // ---- derived view state

  const problemIds = useMemo(() => new Set((problems ?? []).map((p) => p.elementId)), [problems])
  const shownNodes = useMemo(() => nodes.map((n) => (problemIds.has(n.id) ? { ...n, data: { ...n.data, invalid: true } } : n)), [nodes, problemIds])
  const shownEdges = useMemo(
    () => edges.map((e) => decorateEdge(problemIds.has(e.id) ? { ...e, data: { ...e.data, invalid: true } } : e)),
    [edges, problemIds],
  )

  const selectedNode = nodes.filter((n) => n.selected).length === 1 ? nodes.find((n) => n.selected) : undefined
  const selectedEdge = !selectedNode && edges.filter((e) => e.selected).length === 1 ? edges.find((e) => e.selected) : undefined

  const focus = (elementId: string | null) => {
    if (!elementId) return
    setNodes((current) => current.map((n) => ({ ...n, selected: n.id === elementId })))
    setEdges((current) => current.map((e) => ({ ...e, selected: e.id === elementId })))
    const node = nodes.find((n) => n.id === elementId)
    if (node) flow.setCenter(node.position.x + 80, node.position.y + 30, { zoom: 1, duration: 300 })
  }

  const busy = save.isPending || deploy.isPending
  const error = save.error ?? (deploy.error instanceof ApiError && deploy.error.errors.length ? null : deploy.error) ?? validate.error

  return (
    <div className="flex h-full flex-col">
      <div className="flex shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-4 py-2">
        <Link to="/processes" className="text-sm text-slate-500 hover:text-slate-800">
          ← Processes
        </Link>
        <span className="font-mono text-sm font-semibold">{processKey}</span>
        <input className="field !w-56" value={name} onChange={(e) => setName(e.target.value)} placeholder="Display name" />
        {live ? <Badge tone="green">v{live.version} live</Badge> : <Badge>not deployed</Badge>}
        {dirty && <Badge tone="amber">unsaved changes</Badge>}
        <div className="flex-1" />
        <Button variant="ghost" onClick={() => setDialog('versions')}>
          Versions{versions.data ? ` (${versions.data.length})` : ''}
        </Button>
        <Button variant="ghost" disabled={!live} title={live ? '' : 'Deploy the process first'} onClick={() => setDialog('start')}>
          Start instance
        </Button>
        <Button disabled={validate.isPending} onClick={() => validate.mutate()}>
          Validate
        </Button>
        <Button disabled={busy || !dirty} onClick={() => save.mutate()}>
          Save draft
        </Button>
        <Button variant="primary" disabled={busy} onClick={() => { setNotice(null); deploy.mutate() }}>
          Deploy
        </Button>
      </div>

      <div className="flex min-h-0 flex-1">
        <aside className="w-40 shrink-0 space-y-2 border-r border-slate-200 bg-white p-3">
          <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">Add</p>
          {PALETTE.map((item) => (
            <button
              key={item.type}
              draggable
              onDragStart={(e) => e.dataTransfer.setData('application/dotflows', item.type)}
              onClick={() => addNode(item.type)}
              title={item.hint}
              className="block w-full cursor-grab rounded-md border border-slate-300 bg-white px-3 py-2 text-left text-sm font-medium hover:border-indigo-400 hover:bg-indigo-50"
            >
              {item.label}
            </button>
          ))}
          <p className="hint">Click or drag onto the canvas. Drag from a node's right dot to another node to connect them. Backspace deletes the selection.</p>
        </aside>

        <div className="flex min-w-0 flex-1 flex-col">
          <div ref={canvas} className="min-h-0 flex-1" onDrop={onDrop} onDragOver={(e) => e.preventDefault()}>
            <ReactFlow
              nodes={shownNodes}
              edges={shownEdges}
              nodeTypes={nodeTypes}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onConnect={onConnect}
              fitView
              fitViewOptions={{ maxZoom: 1, padding: 0.3 }}
              snapToGrid
              snapGrid={[10, 10]}
            >
              <Background gap={20} color="#cbd5e1" />
              <Controls showInteractive={false} />
            </ReactFlow>
          </div>

          {(error || notice || (problems && problems.length > 0)) && (
            <div className="max-h-44 shrink-0 overflow-auto border-t border-slate-200 bg-white px-4 py-3">
              {error && <ErrorNote error={error} />}
              {notice && !error && <p className="text-sm text-emerald-700">{notice}</p>}
              {problems && problems.length > 0 && (
                <>
                  <p className="mb-1 text-sm font-medium text-red-700">
                    {problems.length} problem{problems.length > 1 ? 's' : ''} to fix before this can be deployed
                  </p>
                  <ul className="space-y-0.5">
                    {problems.map((p, i) => (
                      <li key={i} className="text-sm">
                        {p.elementId && (
                          <button className="mr-2 font-mono text-xs text-indigo-700 hover:underline" onClick={() => focus(p.elementId)}>
                            {p.elementId}
                          </button>
                        )}
                        {p.message}
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </div>
          )}
        </div>

        <aside className="w-80 shrink-0 overflow-auto border-l border-slate-200 bg-white">
          {selectedNode && <NodePanel node={selectedNode.data.node} onChange={updateNode} />}
          {selectedEdge && (
            <EdgePanel
              edge={selectedEdge}
              fromGateway={nodes.find((n) => n.id === selectedEdge.source)?.type === 'exclusiveGateway'}
              onChange={(data) => updateEdge(selectedEdge.id, data)}
            />
          )}
          {!selectedNode && !selectedEdge && (
            <p className="p-4 text-sm text-slate-500">Select a node or a connection to edit it.</p>
          )}
        </aside>
      </div>

      {dialog === 'versions' && (
        <VersionsModal kind="processes" itemKey={processKey} onClose={() => setDialog(null)} onRestore={(d) => load(d as ProcessDefinition)} />
      )}
      {dialog === 'start' && <StartDialog processKey={processKey} onClose={() => setDialog(null)} />}
    </div>
  )
}

export function ProcessEditor() {
  const { key = '' } = useParams()
  // Loaded once per visit: after that the canvas is the source of truth until it is saved.
  const working = useQuery({
    queryKey: ['processes', key, 'working'],
    queryFn: () => api.workingCopy<ProcessDefinition>('processes', key),
    gcTime: 0,
    staleTime: Infinity,
  })
  if (working.error) {
    return (
      <div className="p-6">
        <ErrorNote error={working.error} />
      </div>
    )
  }
  if (!working.data) return <p className="p-6 text-sm text-slate-500">Loading…</p>
  return (
    <ReactFlowProvider key={key}>
      <Editor processKey={key} initial={working.data.definition} />
    </ReactFlowProvider>
  )
}
