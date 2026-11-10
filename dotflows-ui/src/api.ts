// Types and calls for the dot-flows REST API. URLs are relative so the UI works under any base path.

export type Kind = 'processes' | 'decisions'

export type NodeType = 'start' | 'end' | 'task' | 'exclusiveGateway'

export interface RetryPolicy {
  maxAttempts: number
  backoffSeconds: number
}

export interface FlowNode {
  id: string
  name?: string
  type: NodeType
  taskType?: string
  config?: Record<string, unknown>
  retry?: RetryPolicy
}

export interface FlowEdge {
  id: string
  from: string
  to: string
  condition?: string
  default?: boolean
}

export interface ProcessDefinition {
  processKey: string
  name?: string
  nodes: FlowNode[]
  edges: FlowEdge[]
  layout?: Record<string, { x: number; y: number }>
}

export interface Cell {
  op: string
  value?: unknown
}

export interface DecisionRule {
  id?: string
  when: Record<string, Cell>
  then: Record<string, unknown>
}

export interface DecisionTable {
  decisionKey: string
  name?: string
  hitPolicy: 'FIRST' | 'UNIQUE' | 'COLLECT'
  inputs: { id: string; label?: string; expression: string }[]
  outputs: { name: string; label?: string }[]
  rules: DecisionRule[]
}

export interface Definition<T> {
  key: string
  version: number
  status: 'DRAFT' | 'DEPLOYED'
  active: boolean
  name: string | null
  updatedAt: string
  definition: T
}

export interface DefinitionSummary {
  key: string
  name: string | null
  activeVersion: number | null
  deployedAt: string | null
  undeployedChanges: boolean
  draftUpdatedAt: string | null
}

export interface VersionInfo {
  version: number
  active: boolean
  name: string | null
  deployedAt: string
}

export interface ValidationError {
  elementId: string | null
  message: string
}

export interface DelegateDescriptor {
  name: string
  description: string
  inputs: { name: string; description: string; required: boolean }[]
}

export type InstanceStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface Instance {
  id: string
  processKey: string
  version: number
  businessKey: string | null
  status: InstanceStatus
  variables: Record<string, unknown>
  currentNodeId: string | null
  error: string | null
  startedAt: string
  endedAt: string | null
}

export interface Step {
  id: number
  nodeId: string
  nodeType: string
  status: 'COMPLETED' | 'FAILED'
  attempt: number
  startedAt: string
  endedAt: string
  error: string | null
  output: Record<string, unknown> | null
}

export interface InstanceDetail {
  instance: Instance
  steps: Step[]
}

export class ApiError extends Error {
  status: number
  errors: ValidationError[]

  constructor(status: number, message: string, errors: ValidationError[] = []) {
    super(message)
    this.status = status
    this.errors = errors
  }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch('api' + path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  let data: any = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    // not JSON: fall through with the raw text as the message
  }
  if (!response.ok) {
    throw new ApiError(response.status, data?.message ?? data?.error ?? (text || response.statusText), data?.errors ?? [])
  }
  return data as T
}

const enc = encodeURIComponent

export const api = {
  list: (kind: Kind) => call<DefinitionSummary[]>('GET', `/${kind}`),
  workingCopy: <T>(kind: Kind, key: string) => call<Definition<T>>('GET', `/${kind}/${enc(key)}`),
  saveDraft: <T>(kind: Kind, key: string, definition: T) =>
    call<Definition<T>>('PUT', `/${kind}/${enc(key)}/draft`, definition),
  validate: (kind: Kind, definition: unknown) =>
    call<{ errors: ValidationError[] }>('POST', `/${kind}/validate`, definition),
  deploy: <T>(kind: Kind, key: string) => call<Definition<T>>('POST', `/${kind}/${enc(key)}/deploy`),
  versions: (kind: Kind, key: string) => call<VersionInfo[]>('GET', `/${kind}/${enc(key)}/versions`),
  version: <T>(kind: Kind, key: string, version: number) =>
    call<Definition<T>>('GET', `/${kind}/${enc(key)}/versions/${version}`),
  activate: (kind: Kind, key: string, version: number) =>
    call<unknown>('POST', `/${kind}/${enc(key)}/versions/${version}/activate`),
  remove: (kind: Kind, key: string) => call<void>('DELETE', `/${kind}/${enc(key)}`),
  evaluate: (key: string, definition: DecisionTable, variables: Record<string, unknown>) =>
    call<{ result: unknown }>('POST', `/decisions/${enc(key)}/evaluate`, { definition, variables }),
  start: (key: string, variables: Record<string, unknown>, businessKey?: string) =>
    call<{ instanceId: string }>('POST', `/processes/${enc(key)}/start`, { variables, businessKey }),
  instances: (filter: { processKey?: string; status?: string; businessKey?: string }) => {
    const query = new URLSearchParams()
    Object.entries(filter).forEach(([k, v]) => v && query.set(k, v))
    return call<Instance[]>('GET', `/instances?${query}`)
  },
  instance: (id: string) => call<InstanceDetail>('GET', `/instances/${enc(id)}`),
  retry: (id: string) => call<InstanceDetail>('POST', `/instances/${enc(id)}/retry`),
  cancel: (id: string) => call<InstanceDetail>('POST', `/instances/${enc(id)}/cancel`),
  delegates: () => call<DelegateDescriptor[]>('GET', '/delegates'),
}

export const KEY_PATTERN = /^[A-Za-z][A-Za-z0-9_-]{0,99}$/

export function formatTime(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : '—'
}
