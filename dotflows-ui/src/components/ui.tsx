import { useEffect, type ButtonHTMLAttributes, type ReactNode } from 'react'
import type { InstanceStatus } from '../api'

type Variant = 'primary' | 'secondary' | 'danger' | 'ghost'

const variants: Record<Variant, string> = {
  primary: 'bg-indigo-600 text-white hover:bg-indigo-700 border-transparent',
  secondary: 'bg-white text-slate-700 hover:bg-slate-50 border-slate-300',
  danger: 'bg-white text-red-700 hover:bg-red-50 border-red-300',
  ghost: 'bg-transparent text-slate-600 hover:bg-slate-100 border-transparent',
}

export function Button({
  variant = 'secondary',
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      type="button"
      {...props}
      className={`inline-flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${variants[variant]} ${className}`}
    />
  )
}

const tones = {
  green: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  red: 'bg-red-50 text-red-700 ring-red-200',
  blue: 'bg-blue-50 text-blue-700 ring-blue-200',
  amber: 'bg-amber-50 text-amber-800 ring-amber-200',
  slate: 'bg-slate-100 text-slate-600 ring-slate-200',
}

export function Badge({ tone = 'slate', children }: { tone?: keyof typeof tones; children: ReactNode }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${tones[tone]}`}>
      {children}
    </span>
  )
}

export function StatusBadge({ status }: { status: InstanceStatus }) {
  const tone = { RUNNING: 'blue', COMPLETED: 'green', FAILED: 'red', CANCELLED: 'slate' }[status] as keyof typeof tones
  return <Badge tone={tone}>{status.toLowerCase()}</Badge>
}

export function Modal({
  title,
  onClose,
  children,
  footer,
  wide,
}: {
  title: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  wide?: boolean
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center bg-slate-900/40 p-4 pt-[10vh]" onMouseDown={onClose}>
      <div
        className={`w-full ${wide ? 'max-w-3xl' : 'max-w-md'} rounded-lg bg-white shadow-xl`}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className="border-b border-slate-200 px-5 py-3 text-base font-semibold">{title}</div>
        <div className="max-h-[65vh] overflow-auto px-5 py-4">{children}</div>
        {footer && <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">{footer}</div>}
      </div>
    </div>
  )
}

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null
  const message = error instanceof Error ? error.message : String(error)
  return <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800">{message}</div>
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="rounded-lg border border-dashed border-slate-300 p-10 text-center text-sm text-slate-500">{children}</div>
}

export function Page({ title, actions, children }: { title: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <div className="mx-auto max-w-6xl px-6 py-6">
      <div className="mb-5 flex items-center justify-between gap-4">
        <h1 className="text-xl font-semibold">{title}</h1>
        <div className="flex items-center gap-2">{actions}</div>
      </div>
      {children}
    </div>
  )
}

/** Key/value rows where both sides are free text; used for delegate inputs and HTTP headers. */
export function KeyValueEditor({
  value,
  onChange,
  keyPlaceholder,
  valuePlaceholder,
  fixedKeys = [],
}: {
  value: Record<string, unknown>
  onChange: (next: Record<string, unknown>) => void
  keyPlaceholder: string
  valuePlaceholder: string
  fixedKeys?: string[]
}) {
  const entries = Object.entries(value)
  const set = (index: number, key: string, val: unknown) => {
    const next = entries.map(([k, v], i) => (i === index ? [key, val] : [k, v]))
    onChange(Object.fromEntries(next))
  }
  const display = (v: unknown) => (typeof v === 'string' ? v : v == null ? '' : JSON.stringify(v))
  return (
    <div className="space-y-1.5">
      {entries.map(([k, v], i) => (
        <div key={i} className="flex gap-1.5">
          <input
            className="field field-mono !w-2/5"
            value={k}
            placeholder={keyPlaceholder}
            disabled={fixedKeys.includes(k)}
            onChange={(e) => set(i, e.target.value, v)}
          />
          <input
            className="field field-mono"
            value={display(v)}
            placeholder={valuePlaceholder}
            onChange={(e) => set(i, k, e.target.value)}
          />
          {!fixedKeys.includes(k) && (
            <button
              type="button"
              className="px-1 text-slate-400 hover:text-red-600"
              title="Remove"
              onClick={() => onChange(Object.fromEntries(entries.filter((_, j) => j !== i)))}
            >
              ✕
            </button>
          )}
        </div>
      ))}
      <button
        type="button"
        className="text-xs font-medium text-indigo-600 hover:underline"
        onClick={() => {
          let name = 'name'
          for (let n = 2; name in value; n++) name = 'name' + n
          onChange({ ...value, [name]: '' })
        }}
      >
        + Add
      </button>
    </div>
  )
}
