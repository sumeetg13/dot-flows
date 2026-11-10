import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, formatTime, type Kind } from '../api'
import { Badge, Button, Empty, ErrorNote, Modal } from './ui'

type DiffLine = { kind: 'same' | 'added' | 'removed'; text: string }

/** Line diff by longest common subsequence; definitions are small enough for the quadratic table. */
function diffLines(before: string[], after: string[]): DiffLine[] {
  const rows = before.length
  const cols = after.length
  const lcs: number[][] = Array.from({ length: rows + 1 }, () => new Array<number>(cols + 1).fill(0))
  for (let i = rows - 1; i >= 0; i--) {
    for (let j = cols - 1; j >= 0; j--) {
      lcs[i][j] = before[i] === after[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1])
    }
  }
  const out: DiffLine[] = []
  let i = 0
  let j = 0
  while (i < rows && j < cols) {
    if (before[i] === after[j]) {
      out.push({ kind: 'same', text: before[i] })
      i++
      j++
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push({ kind: 'removed', text: before[i++] })
    } else {
      out.push({ kind: 'added', text: after[j++] })
    }
  }
  while (i < rows) out.push({ kind: 'removed', text: before[i++] })
  while (j < cols) out.push({ kind: 'added', text: after[j++] })
  return out
}

/** Canvas positions are noise when comparing what a version does. */
function comparable(definition: unknown): string[] {
  const { layout: _layout, ...rest } = definition as Record<string, unknown>
  return JSON.stringify(rest, null, 2).split('\n')
}

function Diff({ kind, itemKey, from, to }: { kind: Kind; itemKey: string; from: number; to: number }) {
  const before = useQuery({ queryKey: [kind, itemKey, 'version', from], queryFn: () => api.version<unknown>(kind, itemKey, from) })
  const after = useQuery({ queryKey: [kind, itemKey, 'version', to], queryFn: () => api.version<unknown>(kind, itemKey, to) })
  if (before.error || after.error) return <ErrorNote error={before.error ?? after.error} />
  if (!before.data || !after.data) return <p className="text-sm text-slate-500">Loading…</p>
  const lines = diffLines(comparable(before.data.definition), comparable(after.data.definition))
  if (lines.every((l) => l.kind === 'same')) {
    return <p className="text-sm text-slate-500">These versions differ only in diagram layout.</p>
  }
  // Keep three lines of context around each change and fold the rest.
  const near = lines.map((_, i) => lines.slice(Math.max(0, i - 3), i + 4).some((l) => l.kind !== 'same'))
  return (
    <pre className="overflow-auto rounded-md border border-slate-200 bg-slate-50 py-2 font-mono text-xs leading-5">
      {lines.map((line, index) =>
        near[index] ? (
          <div
            key={index}
            className={
              line.kind === 'added' ? 'bg-emerald-100 text-emerald-900' : line.kind === 'removed' ? 'bg-red-100 text-red-900' : 'text-slate-600'
            }
          >
            <span className="inline-block w-6 select-none text-center">{line.kind === 'added' ? '+' : line.kind === 'removed' ? '−' : ''}</span>
            {line.text}
          </div>
        ) : near[index - 1] !== false ? (
          <div key={index} className="select-none px-6 text-slate-400">
            ⋯
          </div>
        ) : null,
      )}
    </pre>
  )
}

/**
 * Version history of a process or decision: which is live, what changed, switch the live one,
 * or bring an old version back into the editor.
 */
export function VersionsModal({
  kind,
  itemKey,
  onClose,
  onRestore,
}: {
  kind: Kind
  itemKey: string
  onClose: () => void
  /** Called with a deployed version's definition when the user wants to edit from it. */
  onRestore: (definition: unknown) => void
}) {
  const queryClient = useQueryClient()
  const versions = useQuery({ queryKey: [kind, itemKey, 'versions'], queryFn: () => api.versions(kind, itemKey) })
  const [comparing, setComparing] = useState<number | null>(null)

  const activate = useMutation({
    mutationFn: (version: number) => api.activate(kind, itemKey, version),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [kind] }),
  })
  const restore = useMutation({
    mutationFn: (version: number) => api.version<unknown>(kind, itemKey, version),
    onSuccess: (data) => {
      onRestore(data.definition)
      onClose()
    },
  })

  return (
    <Modal title={`Versions of ${itemKey}`} onClose={onClose} wide footer={<Button onClick={onClose}>Close</Button>}>
      <div className="space-y-3">
        <ErrorNote error={versions.error ?? activate.error ?? restore.error} />
        {versions.data?.length === 0 && <Empty>Nothing has been deployed yet.</Empty>}
        {versions.data?.map((v) => (
          <div key={v.version} className="rounded-md border border-slate-200">
            <div className="flex items-center gap-3 px-3 py-2">
              <span className="w-10 font-mono text-sm font-semibold">v{v.version}</span>
              {v.active ? <Badge tone="green">live</Badge> : <Badge>inactive</Badge>}
              <span className="flex-1 text-xs text-slate-500">deployed {formatTime(v.deployedAt)}</span>
              {v.version > 1 && (
                <Button variant="ghost" onClick={() => setComparing(comparing === v.version ? null : v.version)}>
                  {comparing === v.version ? 'Hide changes' : `Changes since v${v.version - 1}`}
                </Button>
              )}
              <Button variant="ghost" disabled={restore.isPending} onClick={() => restore.mutate(v.version)} title="Load this version into the editor as the draft">
                Edit from this
              </Button>
              {!v.active && (
                <Button disabled={activate.isPending} onClick={() => activate.mutate(v.version)} title="New instances will start on this version">
                  Make live
                </Button>
              )}
            </div>
            {comparing === v.version && (
              <div className="border-t border-slate-200 p-3">
                <Diff kind={kind} itemKey={itemKey} from={v.version - 1} to={v.version} />
              </div>
            )}
          </div>
        ))}
        {!!versions.data?.length && (
          <p className="hint">
            Running instances always finish on the version they started with. Making another version live only affects
            instances started afterwards.
          </p>
        )}
      </div>
    </Modal>
  )
}
