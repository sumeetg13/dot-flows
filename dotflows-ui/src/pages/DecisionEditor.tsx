import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, type Cell, type DecisionRule, type DecisionTable, type ValidationError } from '../api'
import { VersionsModal } from '../components/Versions'
import { Badge, Button, ErrorNote } from '../components/ui'

const OPERATORS: { op: string; label: string }[] = [
  { op: 'any', label: 'any' },
  { op: '==', label: '=' },
  { op: '!=', label: '≠' },
  { op: '<', label: '<' },
  { op: '<=', label: '≤' },
  { op: '>', label: '>' },
  { op: '>=', label: '≥' },
  { op: 'in', label: 'one of' },
  { op: 'between', label: 'between' },
  { op: 'expr', label: 'expression' },
]

/** `42`, `true`, `null` and `"quoted"` are read as JSON; anything else is text. */
function parseLiteral(text: string): unknown {
  const trimmed = text.trim()
  if (trimmed === '') return undefined
  try {
    const parsed = JSON.parse(trimmed)
    if (parsed === null || ['number', 'boolean', 'string'].includes(typeof parsed)) return parsed
  } catch {
    // plain text
  }
  return trimmed
}

function formatLiteral(value: unknown): string {
  if (value === undefined) return ''
  if (typeof value === 'string') {
    // Quote text that would otherwise be read back as a number, boolean or null.
    return parseLiteral(value) === value ? value : JSON.stringify(value)
  }
  return JSON.stringify(value)
}

function parseCellValue(op: string, text: string): unknown {
  if (op === 'expr') return text
  if (op === 'in' || op === 'between') {
    return text
      .split(',')
      .map(parseLiteral)
      .filter((v) => v !== undefined)
  }
  return parseLiteral(text)
}

function formatCellValue(op: string, value: unknown): string {
  if (op === 'expr') return typeof value === 'string' ? value : ''
  if (Array.isArray(value)) return value.map(formatLiteral).join(', ')
  return formatLiteral(value)
}

/** Shows what the user typed and reports the parsed value; remount (via key) to load a different value. */
function TextCell({ initial, placeholder, onText }: { initial: string; placeholder?: string; onText: (text: string) => void }) {
  const [text, setText] = useState(initial)
  return (
    <input
      className="field field-mono !py-1"
      value={text}
      placeholder={placeholder}
      onChange={(e) => {
        setText(e.target.value)
        onText(e.target.value)
      }}
    />
  )
}

function withRuleIds(table: DecisionTable): DecisionTable {
  let next = 1
  const used = new Set(table.rules.map((r) => r.id).filter(Boolean))
  const rules = table.rules.map((rule) => {
    if (rule.id) return rule
    while (used.has(`rule${next}`)) next++
    used.add(`rule${next}`)
    return { ...rule, id: `rule${next}` }
  })
  return { ...table, hitPolicy: table.hitPolicy ?? 'FIRST', inputs: table.inputs ?? [], outputs: table.outputs ?? [], rules }
}

function uniqueName(prefix: string, taken: string[]): string {
  for (let n = 1; ; n++) if (!taken.includes(prefix + n)) return prefix + n
}

function Editor({ decisionKey, initial }: { decisionKey: string; initial: DecisionTable }) {
  const queryClient = useQueryClient()
  const [table, setTable] = useState(() => withRuleIds(initial))
  const [saved, setSaved] = useState(() => JSON.stringify(withRuleIds(initial)))
  // Bumped when the table is replaced wholesale, so the text cells reload their values.
  const [generation, setGeneration] = useState(0)
  const [problems, setProblems] = useState<ValidationError[] | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [showVersions, setShowVersions] = useState(false)
  const [sample, setSample] = useState('{\n  \n}')

  const dirty = JSON.stringify(table) !== saved
  const versions = useQuery({ queryKey: ['decisions', decisionKey, 'versions'], queryFn: () => api.versions('decisions', decisionKey) })
  const live = versions.data?.find((v) => v.active)

  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => dirty && e.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const save = useMutation({
    mutationFn: () => api.saveDraft('decisions', decisionKey, table),
    onSuccess: () => {
      setSaved(JSON.stringify(table))
      queryClient.invalidateQueries({ queryKey: ['decisions'] })
    },
  })
  const deploy = useMutation({
    mutationFn: async () => {
      await save.mutateAsync()
      return api.deploy<DecisionTable>('decisions', decisionKey)
    },
    onSuccess: (deployed) => {
      setProblems([])
      setNotice(`Deployed as version ${deployed.version}.`)
      queryClient.invalidateQueries({ queryKey: ['decisions'] })
    },
    onError: (error) => {
      if (error instanceof ApiError && error.errors.length) setProblems(error.errors)
    },
  })
  const evaluate = useMutation({
    mutationFn: () => {
      let variables: unknown
      try {
        variables = JSON.parse(sample.trim() || '{}')
      } catch (e) {
        throw new Error('Sample variables are not valid JSON: ' + (e as Error).message)
      }
      return api.evaluate(decisionKey, table, variables as Record<string, unknown>)
    },
    onError: (error) => {
      if (error instanceof ApiError && error.errors.length) setProblems(error.errors)
    },
  })

  const setRule = (index: number, rule: DecisionRule) =>
    setTable((t) => ({ ...t, rules: t.rules.map((r, i) => (i === index ? rule : r)) }))

  const setCell = (index: number, inputId: string, cell: Cell | undefined) => {
    const rule = table.rules[index]
    const when = { ...rule.when }
    if (!cell || cell.op === 'any') delete when[inputId]
    else when[inputId] = cell
    setRule(index, { ...rule, when })
  }

  const moveRule = (index: number, by: number) =>
    setTable((t) => {
      const rules = [...t.rules]
      const [rule] = rules.splice(index, 1)
      rules.splice(index + by, 0, rule)
      return { ...t, rules }
    })

  const renameOutput = (index: number, name: string) =>
    setTable((t) => {
      const old = t.outputs[index].name
      return {
        ...t,
        outputs: t.outputs.map((o, i) => (i === index ? { ...o, name } : o)),
        rules: t.rules.map((r) => {
          const then: Record<string, unknown> = {}
          for (const [k, v] of Object.entries(r.then)) then[k === old ? name : k] = v
          return { ...r, then }
        }),
      }
    })

  const removeInput = (id: string) =>
    setTable((t) => ({
      ...t,
      inputs: t.inputs.filter((i) => i.id !== id),
      rules: t.rules.map((r) => {
        const { [id]: _removed, ...when } = r.when
        return { ...r, when }
      }),
    }))

  const removeOutput = (name: string) =>
    setTable((t) => ({
      ...t,
      outputs: t.outputs.filter((o) => o.name !== name),
      rules: t.rules.map((r) => {
        const { [name]: _removed, ...then } = r.then
        return { ...r, then }
      }),
    }))

  const error = save.error ?? (deploy.error instanceof ApiError && deploy.error.errors.length ? null : deploy.error)
  const busy = save.isPending || deploy.isPending

  return (
    <div className="flex h-full flex-col">
      <div className="flex shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-4 py-2">
        <Link to="/decisions" className="text-sm text-slate-500 hover:text-slate-800">
          ← Decisions
        </Link>
        <span className="font-mono text-sm font-semibold">{decisionKey}</span>
        <input className="field !w-56" value={table.name ?? ''} onChange={(e) => setTable({ ...table, name: e.target.value || undefined })} placeholder="Display name" />
        {live ? <Badge tone="green">v{live.version} live</Badge> : <Badge>not deployed</Badge>}
        {dirty && <Badge tone="amber">unsaved changes</Badge>}
        <div className="flex-1" />
        <Button variant="ghost" onClick={() => setShowVersions(true)}>
          Versions{versions.data ? ` (${versions.data.length})` : ''}
        </Button>
        <Button disabled={busy || !dirty} onClick={() => save.mutate()}>
          Save draft
        </Button>
        <Button variant="primary" disabled={busy} onClick={() => { setNotice(null); deploy.mutate() }}>
          Deploy
        </Button>
      </div>

      <div className="min-h-0 flex-1 space-y-5 overflow-auto p-6">
        {error && <ErrorNote error={error} />}
        {notice && <p className="text-sm text-emerald-700">{notice}</p>}
        {problems && problems.length > 0 && (
          <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800">
            <p className="font-medium">Fix these before deploying:</p>
            <ul className="mt-1 list-inside list-disc">
              {problems.map((p, i) => (
                <li key={i}>
                  {p.elementId ? <code>{p.elementId}</code> : null} {p.message}
                </li>
              ))}
            </ul>
          </div>
        )}

        <div className="flex items-end gap-4">
          <div>
            <label className="label">When several rules match</label>
            <select className="field !w-72" value={table.hitPolicy} onChange={(e) => setTable({ ...table, hitPolicy: e.target.value as DecisionTable['hitPolicy'] })}>
              <option value="FIRST">First: use the first matching rule</option>
              <option value="UNIQUE">Unique: more than one match is an error</option>
              <option value="COLLECT">Collect: return all matches as a list</option>
            </select>
          </div>
          <p className="hint max-w-xl pb-1">
            {table.outputs.length === 1
              ? 'With one output, the result is that value itself.'
              : 'With several outputs, the result is an object with one field per output.'}{' '}
            Values: <code>42</code>, <code>true</code> and <code>"42"</code> keep their type; anything else is text.
          </p>
        </div>

        <div className="overflow-auto rounded-lg border border-slate-200 bg-white">
          <table className="w-full border-collapse" key={generation}>
            <thead>
              <tr className="bg-slate-50">
                <th className="th w-10">#</th>
                {table.inputs.map((input, index) => (
                  <th key={input.id} className="th min-w-56 border-l border-slate-200 normal-case">
                    <div className="flex items-center justify-between">
                      <span className="uppercase text-indigo-600">Input</span>
                      <button className="text-slate-400 hover:text-red-600" title="Remove column" onClick={() => removeInput(input.id)}>
                        ✕
                      </button>
                    </div>
                    <input
                      className="field mt-1 !py-1 font-normal"
                      value={input.label ?? ''}
                      placeholder="Label"
                      onChange={(e) => setTable({ ...table, inputs: table.inputs.map((x, i) => (i === index ? { ...x, label: e.target.value } : x)) })}
                    />
                    <input
                      className="field field-mono mt-1 !py-1 font-normal"
                      value={input.expression}
                      placeholder="#order.total"
                      onChange={(e) => setTable({ ...table, inputs: table.inputs.map((x, i) => (i === index ? { ...x, expression: e.target.value } : x)) })}
                    />
                  </th>
                ))}
                {table.outputs.map((output, index) => (
                  <th key={index} className="th min-w-44 border-l border-slate-200 bg-emerald-50/60 normal-case">
                    <div className="flex items-center justify-between">
                      <span className="uppercase text-emerald-700">Output</span>
                      <button className="text-slate-400 hover:text-red-600" title="Remove column" onClick={() => removeOutput(output.name)}>
                        ✕
                      </button>
                    </div>
                    <input className="field field-mono mt-1 !py-1 font-normal" value={output.name} placeholder="name" onChange={(e) => renameOutput(index, e.target.value.trim())} />
                  </th>
                ))}
                <th className="th w-24 border-l border-slate-200" />
              </tr>
            </thead>
            <tbody>
              {table.rules.map((rule, index) => (
                <tr key={rule.id}>
                  <td className="td text-slate-400">{index + 1}</td>
                  {table.inputs.map((input) => {
                    const cell = rule.when[input.id]
                    const op = cell?.op ?? 'any'
                    return (
                      <td key={input.id} className="td border-l border-slate-100">
                        <div className="flex gap-1.5">
                          <select
                            className="field !w-28 !py-1"
                            value={op}
                            onChange={(e) => setCell(index, input.id, { op: e.target.value, value: parseCellValue(e.target.value, formatCellValue(op, cell?.value)) })}
                          >
                            {OPERATORS.map((o) => (
                              <option key={o.op} value={o.op}>
                                {o.label}
                              </option>
                            ))}
                          </select>
                          {op !== 'any' && (
                            <TextCell
                              key={op}
                              initial={formatCellValue(op, cell?.value)}
                              placeholder={op === 'in' ? 'a, b, c' : op === 'between' ? 'low, high' : op === 'expr' ? '#input > #limit' : 'value'}
                              onText={(text) => setCell(index, input.id, { op, value: parseCellValue(op, text) })}
                            />
                          )}
                        </div>
                      </td>
                    )
                  })}
                  {table.outputs.map((output, outputIndex) => (
                    <td key={outputIndex} className="td border-l border-slate-100 bg-emerald-50/30">
                      <TextCell
                        initial={formatLiteral(rule.then[output.name])}
                        placeholder="value"
                        onText={(text) => {
                          const then = { ...rule.then }
                          const value = parseLiteral(text)
                          if (value === undefined) delete then[output.name]
                          else then[output.name] = value
                          setRule(index, { ...rule, then })
                        }}
                      />
                    </td>
                  ))}
                  <td className="td whitespace-nowrap border-l border-slate-100 text-right text-slate-400">
                    <button className="px-1 hover:text-slate-800 disabled:opacity-30" disabled={index === 0} title="Move up" onClick={() => moveRule(index, -1)}>
                      ↑
                    </button>
                    <button className="px-1 hover:text-slate-800 disabled:opacity-30" disabled={index === table.rules.length - 1} title="Move down" onClick={() => moveRule(index, 1)}>
                      ↓
                    </button>
                    <button className="px-1 hover:text-red-600" title="Delete rule" onClick={() => setTable({ ...table, rules: table.rules.filter((_, i) => i !== index) })}>
                      ✕
                    </button>
                  </td>
                </tr>
              ))}
              {table.rules.length === 0 && (
                <tr>
                  <td className="td text-center text-slate-500" colSpan={table.inputs.length + table.outputs.length + 2}>
                    No rules yet. Each rule says: when the inputs look like this, the outputs are that.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        <div className="flex gap-2">
          <Button onClick={() => setTable({ ...table, rules: [...table.rules, { id: uniqueName('rule', table.rules.map((r) => r.id ?? '')), when: {}, then: {} }] })}>
            + Rule
          </Button>
          <Button onClick={() => setTable({ ...table, inputs: [...table.inputs, { id: uniqueName('input', table.inputs.map((i) => i.id)), label: '', expression: '' }] })}>
            + Input column
          </Button>
          <Button onClick={() => setTable({ ...table, outputs: [...table.outputs, { name: uniqueName('output', table.outputs.map((o) => o.name)) }] })}>
            + Output column
          </Button>
        </div>

        <div className="rounded-lg border border-slate-200 bg-white p-4">
          <h2 className="mb-1 text-sm font-semibold">Try it</h2>
          <p className="hint mb-3">Evaluates the table as it is on screen, saved or not, against the variables you give.</p>
          <div className="flex gap-4">
            <textarea className="field field-mono !w-1/2" rows={6} value={sample} onChange={(e) => setSample(e.target.value)} placeholder={'{ "order": { "total": 250 } }'} />
            <div className="flex-1 space-y-2">
              <Button disabled={evaluate.isPending} onClick={() => evaluate.mutate()}>
                Evaluate
              </Button>
              {evaluate.error && <ErrorNote error={evaluate.error} />}
              {evaluate.data && !evaluate.error && (
                <pre className="overflow-auto rounded-md bg-slate-900 p-3 font-mono text-xs text-slate-100">
                  {evaluate.data.result === null ? 'null  (no rule matched)' : JSON.stringify(evaluate.data.result, null, 2)}
                </pre>
              )}
            </div>
          </div>
        </div>
      </div>

      {showVersions && (
        <VersionsModal
          kind="decisions"
          itemKey={decisionKey}
          onClose={() => setShowVersions(false)}
          onRestore={(restored) => {
            setTable(withRuleIds({ ...(restored as DecisionTable), decisionKey }))
            setGeneration((g) => g + 1)
            setProblems(null)
            setNotice('Loaded into the editor. Save or deploy to keep it.')
          }}
        />
      )}
    </div>
  )
}

export function DecisionEditor() {
  const { key = '' } = useParams()
  const working = useQuery({
    queryKey: ['decisions', key, 'working'],
    queryFn: () => api.workingCopy<DecisionTable>('decisions', key),
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
  return <Editor key={key} decisionKey={key} initial={working.data.definition} />
}
