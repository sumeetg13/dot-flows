import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, formatTime, KEY_PATTERN, type DecisionTable, type Kind } from '../api'
import { blankProcess } from '../flow'
import { Badge, Button, Empty, ErrorNote, Modal, Page } from '../components/ui'

function blankDecision(decisionKey: string, name: string): DecisionTable {
  return {
    decisionKey,
    name: name || undefined,
    hitPolicy: 'FIRST',
    inputs: [{ id: 'input1', label: 'Input', expression: '' }],
    outputs: [{ name: 'result' }],
    rules: [],
  }
}

export function DefinitionList({ kind }: { kind: Kind }) {
  const noun = kind === 'processes' ? 'process' : 'decision'
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const list = useQuery({ queryKey: [kind], queryFn: () => api.list(kind) })
  const [creating, setCreating] = useState(false)
  const [key, setKey] = useState('')
  const [name, setName] = useState('')

  const create = useMutation({
    mutationFn: () =>
      api.saveDraft(kind, key, kind === 'processes' ? blankProcess(key, name) : blankDecision(key, name)),
    onSuccess: () => navigate(`/${kind}/${key}`),
  })
  const remove = useMutation({
    mutationFn: (target: string) => api.remove(kind, target),
    onSettled: () => queryClient.invalidateQueries({ queryKey: [kind] }),
  })

  const exists = list.data?.some((d) => d.key === key)
  const keyProblem = !key
    ? null
    : !KEY_PATTERN.test(key)
      ? 'Start with a letter; use letters, digits, - and _ only.'
      : exists
        ? `A ${noun} with this key already exists.`
        : null

  return (
    <Page
      title={kind === 'processes' ? 'Processes' : 'Decisions'}
      actions={
        <Button variant="primary" onClick={() => { setKey(''); setName(''); create.reset(); setCreating(true) }}>
          New {noun}
        </Button>
      }
    >
      <ErrorNote error={list.error ?? remove.error} />
      {list.data?.length === 0 && (
        <Empty>
          No {kind} yet. Create one to start modeling
          {kind === 'decisions' ? '; processes use decisions through a decision task.' : '.'}
        </Empty>
      )}
      {!!list.data?.length && (
        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <table className="w-full">
            <thead>
              <tr>
                <th className="th">Key</th>
                <th className="th">Name</th>
                <th className="th">Deployed</th>
                <th className="th">Last deployed</th>
                <th className="th" />
              </tr>
            </thead>
            <tbody>
              {list.data.map((d) => (
                <tr key={d.key} className="hover:bg-slate-50">
                  <td className="td">
                    <Link to={`/${kind}/${d.key}`} className="font-mono font-medium text-indigo-700 hover:underline">
                      {d.key}
                    </Link>
                  </td>
                  <td className="td text-slate-600">{d.name ?? '—'}</td>
                  <td className="td">
                    <span className="flex items-center gap-2">
                      {d.activeVersion == null ? <Badge>not deployed</Badge> : <Badge tone="green">v{d.activeVersion} live</Badge>}
                      {d.undeployedChanges && d.activeVersion != null && <Badge tone="amber">draft has changes</Badge>}
                    </span>
                  </td>
                  <td className="td text-slate-600">{formatTime(d.deployedAt)}</td>
                  <td className="td text-right">
                    <button
                      className="text-xs text-slate-400 hover:text-red-600"
                      onClick={() => {
                        if (window.confirm(`Delete ${noun} "${d.key}" and all its versions? This cannot be undone.`)) {
                          remove.mutate(d.key)
                        }
                      }}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {creating && (
        <Modal
          title={`New ${noun}`}
          onClose={() => setCreating(false)}
          footer={
            <>
              <Button onClick={() => setCreating(false)}>Cancel</Button>
              <Button variant="primary" disabled={!key || !!keyProblem || create.isPending} onClick={() => create.mutate()}>
                Create
              </Button>
            </>
          }
        >
          <div className="space-y-4">
            <div>
              <label className="label">Key</label>
              <input className="field field-mono" autoFocus value={key} onChange={(e) => setKey(e.target.value.trim())} placeholder={kind === 'processes' ? 'order-notification' : 'notify-channel'} />
              <p className={`hint ${keyProblem ? '!text-red-600' : ''}`}>
                {keyProblem ?? 'The permanent id your code and other definitions refer to. It cannot be changed later.'}
              </p>
            </div>
            <div>
              <label className="label">Name</label>
              <input className="field" value={name} onChange={(e) => setName(e.target.value)} placeholder="Optional display name" />
            </div>
            <ErrorNote error={create.error} />
          </div>
        </Modal>
      )}
    </Page>
  )
}
