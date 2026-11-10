import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, type FlowNode } from '../api'
import type { EdgeData, RFEdge } from '../flow'
import { KeyValueEditor } from './ui'

const EXPRESSION_HINT = (
  <>
    Expressions read process variables: <code>#order.total</code>, <code>#customer.email</code>. Write literal text in
    quotes: <code>'hello'</code>.
  </>
)

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="border-b border-slate-200 px-4 py-4">
      <h3 className="mb-3 text-xs font-semibold uppercase tracking-wide text-slate-500">{title}</h3>
      <div className="space-y-3">{children}</div>
    </div>
  )
}

function DelegateForm({ node, setConfig }: { node: FlowNode; setConfig: (patch: Record<string, unknown>) => void }) {
  const delegates = useQuery({ queryKey: ['delegates'], queryFn: api.delegates })
  const selected = delegates.data?.find((d) => d.name === node.config?.delegate)
  const inputs = (node.config?.inputs as Record<string, unknown>) ?? {}
  const unknown = !!node.config?.delegate && delegates.data && !selected

  const choose = (name: string) => {
    const declared = delegates.data?.find((d) => d.name === name)?.inputs ?? []
    const merged: Record<string, unknown> = {}
    for (const input of declared) merged[input.name] = inputs[input.name] ?? ''
    setConfig({ delegate: name || undefined, inputs: { ...merged, ...inputs } })
  }

  return (
    <Section title="Delegate">
      <div>
        <label className="label">Java delegate</label>
        <select className="field" value={(node.config?.delegate as string) ?? ''} onChange={(e) => choose(e.target.value)}>
          <option value="">Choose…</option>
          {unknown && <option value={node.config!.delegate as string}>{node.config!.delegate as string} (not registered)</option>}
          {delegates.data?.map((d) => (
            <option key={d.name} value={d.name}>
              {d.name}
            </option>
          ))}
        </select>
        {selected?.description && <p className="hint">{selected.description}</p>}
        {delegates.data?.length === 0 && (
          <p className="hint">
            No delegates found. Add a Spring bean that implements <code>TaskDelegate</code> and is annotated with{' '}
            <code>@FlowDelegate("name")</code>.
          </p>
        )}
      </div>
      <div>
        <label className="label">Inputs</label>
        <KeyValueEditor
          value={inputs}
          onChange={(next) => setConfig({ inputs: next })}
          keyPlaceholder="name"
          valuePlaceholder="expression"
          fixedKeys={selected?.inputs.map((i) => i.name)}
        />
        {selected?.inputs.some((i) => i.description || i.required) && (
          <ul className="hint space-y-0.5">
            {selected.inputs.map((i) => (
              <li key={i.name}>
                <code>{i.name}</code>
                {i.required ? ' (required)' : ''}
                {i.description ? `: ${i.description}` : ''}
              </li>
            ))}
          </ul>
        )}
        <p className="hint">{EXPRESSION_HINT}</p>
      </div>
    </Section>
  )
}

/** The body is kept as typed until the field loses focus, then stored as JSON when it parses as an object or array. */
function BodyField({ node, setConfig }: { node: FlowNode; setConfig: (patch: Record<string, unknown>) => void }) {
  const stored = node.config?.body
  const asText = stored == null ? '' : typeof stored === 'string' ? stored : JSON.stringify(stored, null, 2)
  const [text, setText] = useState(asText)
  useEffect(() => setText(asText), [node.id]) // eslint-disable-line react-hooks/exhaustive-deps

  const commit = () => {
    const trimmed = text.trim()
    if (!trimmed) return setConfig({ body: undefined })
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
      try {
        return setConfig({ body: JSON.parse(trimmed) })
      } catch {
        // not valid JSON: send it as text
      }
    }
    setConfig({ body: text })
  }

  return (
    <div>
      <label className="label">Body</label>
      <textarea className="field field-mono" rows={6} value={text} onChange={(e) => setText(e.target.value)} onBlur={commit} placeholder={'{\n  "to": "#{#customer.phone}"\n}'} />
      <p className="hint">A JSON object is sent as application/json; anything else as plain text.</p>
    </div>
  )
}

function HttpForm({ node, setConfig }: { node: FlowNode; setConfig: (patch: Record<string, unknown>) => void }) {
  const config = node.config ?? {}
  return (
    <Section title="HTTP request">
      <div className="flex gap-2">
        <select className="field !w-28" value={(config.method as string) ?? 'GET'} onChange={(e) => setConfig({ method: e.target.value })}>
          {['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => (
            <option key={m}>{m}</option>
          ))}
        </select>
        <input className="field field-mono" value={(config.url as string) ?? ''} onChange={(e) => setConfig({ url: e.target.value })} placeholder="https://api.example.com/sms" />
      </div>
      <p className="hint">
        In the URL, headers and body, <code>{'#{#variable}'}</code> inserts a process variable and <code>{'${name}'}</code>{' '}
        inserts a secret configured under <code>dotflows.http.variables</code>.
      </p>
      <div>
        <label className="label">Headers</label>
        <KeyValueEditor value={(config.headers as Record<string, unknown>) ?? {}} onChange={(next) => setConfig({ headers: next })} keyPlaceholder="Header" valuePlaceholder="value" />
      </div>
      <BodyField node={node} setConfig={setConfig} />
      <div className="flex gap-2">
        <div className="flex-1">
          <label className="label">Store response in variable</label>
          <input className="field field-mono" value={(config.resultVariable as string) ?? ''} onChange={(e) => setConfig({ resultVariable: e.target.value || undefined })} placeholder="response" />
        </div>
        <div className="w-28">
          <label className="label">Timeout (s)</label>
          <input className="field" type="number" min={1} value={(config.timeoutSeconds as number) ?? ''} onChange={(e) => setConfig({ timeoutSeconds: e.target.value ? Number(e.target.value) : undefined })} placeholder="30" />
        </div>
      </div>
      <p className="hint">
        The variable receives <code>{'{status, body}'}</code>, so a later condition can read <code>#response.body.id</code>.
      </p>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={config.failOnErrorStatus !== false} onChange={(e) => setConfig({ failOnErrorStatus: e.target.checked ? undefined : false })} />
        Fail and retry the task on a 4xx or 5xx response
      </label>
    </Section>
  )
}

function DecisionForm({ node, setConfig }: { node: FlowNode; setConfig: (patch: Record<string, unknown>) => void }) {
  const decisions = useQuery({ queryKey: ['decisions'], queryFn: () => api.list('decisions') })
  const config = node.config ?? {}
  const current = decisions.data?.find((d) => d.key === config.decisionKey)
  return (
    <Section title="Decision">
      <div>
        <label className="label">Decision table</label>
        <select className="field" value={(config.decisionKey as string) ?? ''} onChange={(e) => setConfig({ decisionKey: e.target.value || undefined })}>
          <option value="">Choose…</option>
          {decisions.data?.map((d) => (
            <option key={d.key} value={d.key}>
              {d.key}
              {d.activeVersion == null ? ' (not deployed)' : ''}
            </option>
          ))}
        </select>
        {current && current.activeVersion == null && <p className="hint !text-amber-700">Deploy this decision before deploying the process.</p>}
      </div>
      <div className="flex gap-2">
        <div className="flex-1">
          <label className="label">Store result in variable</label>
          <input className="field field-mono" value={(config.resultVariable as string) ?? ''} onChange={(e) => setConfig({ resultVariable: e.target.value || undefined })} placeholder="channel" />
        </div>
        <div className="w-28">
          <label className="label">Version</label>
          <input className="field" type="number" min={1} value={(config.decisionVersion as number) ?? ''} onChange={(e) => setConfig({ decisionVersion: e.target.value ? Number(e.target.value) : undefined })} placeholder="live" />
        </div>
      </div>
      <p className="hint">Leave version empty to always use the decision's live version.</p>
    </Section>
  )
}

export function NodePanel({ node, onChange }: { node: FlowNode; onChange: (next: FlowNode) => void }) {
  const setConfig = (patch: Record<string, unknown>) => {
    const config: Record<string, unknown> = { ...(node.config ?? {}), ...patch }
    for (const key of Object.keys(config)) if (config[key] === undefined) delete config[key]
    onChange({ ...node, config })
  }
  const kindLabel = { start: 'Start', end: 'End', task: 'Task', exclusiveGateway: 'Gateway' }[node.type]

  return (
    <div>
      <Section title={kindLabel}>
        <div>
          <label className="label">Name</label>
          <input className="field" value={node.name ?? ''} onChange={(e) => onChange({ ...node, name: e.target.value })} placeholder={node.id} />
          <p className="hint">
            Id: <code>{node.id}</code>
          </p>
        </div>
        {node.type === 'task' && (
          <div>
            <label className="label">Task type</label>
            <select className="field" value={node.taskType ?? 'delegate'} onChange={(e) => onChange({ ...node, taskType: e.target.value, config: {} })}>
              <option value="delegate">Delegate: run Java code</option>
              <option value="http">HTTP: call an API</option>
              <option value="decision">Decision: evaluate a table</option>
            </select>
          </div>
        )}
        {node.type === 'exclusiveGateway' && (
          <p className="hint">
            Takes the first outgoing connection whose condition is true, otherwise the default one. Select a connection
            to set its condition.
          </p>
        )}
      </Section>

      {node.type === 'task' && node.taskType === 'delegate' && <DelegateForm node={node} setConfig={setConfig} />}
      {node.type === 'task' && node.taskType === 'http' && <HttpForm node={node} setConfig={setConfig} />}
      {node.type === 'task' && node.taskType === 'decision' && <DecisionForm node={node} setConfig={setConfig} />}

      {node.type === 'task' && (
        <Section title="If the task fails">
          <div className="flex gap-2">
            <div className="flex-1">
              <label className="label">Attempts</label>
              <input
                className="field"
                type="number"
                min={1}
                value={node.retry?.maxAttempts ?? ''}
                placeholder="default"
                onChange={(e) =>
                  onChange({
                    ...node,
                    retry: e.target.value ? { maxAttempts: Number(e.target.value), backoffSeconds: node.retry?.backoffSeconds ?? 10 } : undefined,
                  })
                }
              />
            </div>
            <div className="flex-1">
              <label className="label">Wait between (s)</label>
              <input
                className="field"
                type="number"
                min={0}
                disabled={!node.retry}
                value={node.retry?.backoffSeconds ?? ''}
                onChange={(e) => node.retry && onChange({ ...node, retry: { ...node.retry, backoffSeconds: Number(e.target.value || 0) } })}
              />
            </div>
          </div>
          <p className="hint">After the last attempt the instance is marked failed and can be retried from the Instances page.</p>
        </Section>
      )}
    </div>
  )
}

export function EdgePanel({
  edge,
  fromGateway,
  onChange,
}: {
  edge: RFEdge
  fromGateway: boolean
  onChange: (data: EdgeData) => void
}) {
  const data = edge.data ?? {}
  return (
    <Section title="Connection">
      <p className="text-sm text-slate-600">
        <code>{edge.source}</code> → <code>{edge.target}</code>
      </p>
      {fromGateway ? (
        <>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={!!data.isDefault} onChange={(e) => onChange({ ...data, isDefault: e.target.checked })} />
            Default: taken when no other condition matches
          </label>
          {!data.isDefault && (
            <div>
              <label className="label">Condition</label>
              <textarea className="field field-mono" rows={3} value={data.condition ?? ''} onChange={(e) => onChange({ ...data, condition: e.target.value })} placeholder="#channel == 'EMAIL'" />
              <p className="hint">
                An expression that is true or false, for example <code>#order.total &gt; 100</code> or{' '}
                <code>#response.status == 200 and #retries &lt; 3</code>.
              </p>
            </div>
          )}
        </>
      ) : (
        <p className="hint">Only connections leaving a gateway carry conditions.</p>
      )}
    </Section>
  )
}
