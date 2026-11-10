// Shared between the modeler and the instance view: how a process definition is drawn.
import { Handle, MarkerType, Position, type Edge, type Node, type NodeProps } from '@xyflow/react'
import type { FlowEdge, FlowNode, NodeType, ProcessDefinition } from './api'

export type NodeState = 'done' | 'failed' | 'current'

export type NodeData = {
  node: FlowNode
  /** Set by the instance view. */
  state?: NodeState
  /** Set by the modeler when validation reports a problem on this node. */
  invalid?: boolean
}

export type EdgeData = { condition?: string; isDefault?: boolean; invalid?: boolean; taken?: boolean }

export type RFNode = Node<NodeData>
export type RFEdge = Edge<EdgeData>

const TASK_ICONS: Record<string, string> = { delegate: '⚙', http: '⇄', decision: '▦' }

function frame(data: NodeData, selected: boolean | undefined): string {
  if (data.invalid) return 'border-red-500 bg-red-50'
  if (data.state === 'failed') return 'border-red-500 bg-red-50'
  if (data.state === 'current') return 'border-blue-500 bg-blue-50'
  if (data.state === 'done') return 'border-emerald-500 bg-emerald-50'
  return selected ? 'border-indigo-500 bg-white' : 'border-slate-400 bg-white'
}

const ring = (selected: boolean | undefined) => (selected ? 'ring-2 ring-indigo-200' : '')

function StartNode({ data, selected }: NodeProps<RFNode>) {
  return (
    <div className={`flex h-11 w-11 items-center justify-center rounded-full border-2 text-[10px] font-medium text-slate-600 ${frame(data, selected)} ${ring(selected)}`}>
      start
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

function EndNode({ data, selected }: NodeProps<RFNode>) {
  return (
    <div className={`flex h-11 w-11 items-center justify-center rounded-full border-4 text-[10px] font-medium text-slate-600 ${frame(data, selected)} ${ring(selected)}`}>
      <Handle type="target" position={Position.Left} />
      end
    </div>
  )
}

function TaskNode({ data, selected }: NodeProps<RFNode>) {
  const { node } = data
  return (
    <div className={`w-44 rounded-lg border-2 px-3 py-2 shadow-sm ${frame(data, selected)} ${ring(selected)}`}>
      <Handle type="target" position={Position.Left} />
      <div className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        <span>{TASK_ICONS[node.taskType ?? ''] ?? '•'}</span>
        {node.taskType ?? 'task'}
      </div>
      <div className="truncate text-sm font-medium text-slate-800">{node.name || node.id}</div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

function GatewayNode({ data, selected }: NodeProps<RFNode>) {
  return (
    <div className="relative flex h-14 w-14 items-center justify-center" title={data.node.name || data.node.id}>
      <Handle type="target" position={Position.Left} />
      <div className={`absolute inset-[7px] rotate-45 border-2 ${frame(data, selected)} ${ring(selected)}`} />
      <span className="relative text-sm font-bold text-slate-600">✕</span>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

export const nodeTypes = {
  start: StartNode,
  end: EndNode,
  task: TaskNode,
  exclusiveGateway: GatewayNode,
}

/** Left-to-right positions for definitions that carry no layout, e.g. ones deployed from code. */
function autoLayout(definition: ProcessDefinition): Record<string, { x: number; y: number }> {
  const depth: Record<string, number> = {}
  const start = definition.nodes.find((n) => n.type === 'start')
  const queue = start ? [start.id] : []
  if (start) depth[start.id] = 0
  while (queue.length) {
    const id = queue.shift()!
    for (const edge of definition.edges.filter((e) => e.from === id)) {
      if (depth[edge.to] === undefined) {
        depth[edge.to] = depth[id] + 1
        queue.push(edge.to)
      }
    }
  }
  const rows: Record<number, number> = {}
  const positions: Record<string, { x: number; y: number }> = {}
  for (const node of definition.nodes) {
    const column = depth[node.id] ?? 0
    const row = rows[column] ?? 0
    rows[column] = row + 1
    positions[node.id] = { x: 60 + column * 240, y: 80 + row * 120 }
  }
  return positions
}

export function edgeId(edge: Pick<FlowEdge, 'id' | 'from' | 'to'>): string {
  return edge.id || `e_${edge.from}_${edge.to}`
}

export function toFlow(definition: ProcessDefinition): { nodes: RFNode[]; edges: RFEdge[] } {
  const fallback = autoLayout(definition)
  const nodes: RFNode[] = definition.nodes.map((node) => ({
    id: node.id,
    type: node.type,
    position: definition.layout?.[node.id] ?? fallback[node.id],
    data: { node },
  }))
  const edges: RFEdge[] = definition.edges.map((edge) => ({
    id: edgeId(edge),
    source: edge.from,
    target: edge.to,
    data: { condition: edge.condition, isDefault: edge.default },
  }))
  return { nodes, edges }
}

export function fromFlow(processKey: string, name: string | undefined, nodes: RFNode[], edges: RFEdge[]): ProcessDefinition {
  const layout: Record<string, { x: number; y: number }> = {}
  for (const node of nodes) {
    layout[node.id] = { x: Math.round(node.position.x), y: Math.round(node.position.y) }
  }
  return {
    processKey,
    name: name || undefined,
    nodes: nodes.map((n) => {
      const { id, name: nodeName, type, taskType, config, retry } = n.data.node
      return type === 'task'
        ? { id, name: nodeName || undefined, type, taskType, config: config ?? {}, retry }
        : { id, name: nodeName || undefined, type }
    }),
    edges: edges.map((e) => ({
      id: e.id,
      from: e.source,
      to: e.target,
      condition: e.data?.condition?.trim() ? e.data.condition : undefined,
      default: e.data?.isDefault ? true : undefined,
    })),
    layout,
  }
}

/** Adds the label, arrowhead and colours an edge is drawn with. */
export function decorateEdge(edge: RFEdge): RFEdge {
  const data = edge.data ?? {}
  const color = data.invalid ? '#ef4444' : data.taken ? '#10b981' : edge.selected ? '#6366f1' : '#94a3b8'
  return {
    ...edge,
    label: data.isDefault ? 'default' : data.condition || undefined,
    labelBgPadding: [4, 2],
    labelStyle: { fill: data.invalid ? '#b91c1c' : '#475569', fontFamily: 'ui-monospace, monospace' },
    style: { stroke: color, strokeWidth: data.taken || edge.selected ? 2 : 1.5, strokeDasharray: data.isDefault ? '5 4' : undefined },
    markerEnd: { type: MarkerType.ArrowClosed, color },
  }
}

export function newNodeId(type: NodeType, existing: string[]): string {
  const prefix = type === 'exclusiveGateway' ? 'gateway' : type
  for (let n = 1; ; n++) {
    const id = `${prefix}_${n}`
    if (!existing.includes(id)) return id
  }
}

export function blankProcess(processKey: string, name: string): ProcessDefinition {
  return {
    processKey,
    name: name || undefined,
    nodes: [
      { id: 'start', type: 'start' },
      { id: 'end', type: 'end' },
    ],
    edges: [{ id: 'e_start_end', from: 'start', to: 'end' }],
    layout: { start: { x: 80, y: 160 }, end: { x: 520, y: 160 } },
  }
}
