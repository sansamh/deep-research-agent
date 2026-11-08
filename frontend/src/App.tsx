import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { deleteSession, getSession, listSessions, startResearch } from './api'
import type { Session, SessionSummary, Step } from './types'

const EXAMPLES = [
  '2026 年东南亚电动车市场格局与主要玩家',
  'Impact of AI coding assistants on software engineering productivity',
  'Solid-state battery commercialization timeline',
]

interface TraceLine {
  stepId: number | string
  text: string
}

export default function App() {
  const [topic, setTopic] = useState('')
  const [phase, setPhase] = useState<'idle' | 'running' | 'done' | 'error'>('idle')
  const [error, setError] = useState<string | null>(null)
  const [sessionId, setSessionId] = useState<number | null>(null)
  const [questions, setQuestions] = useState<string[]>([])
  const [steps, setSteps] = useState<Step[]>([])
  const [traces, setTraces] = useState<TraceLine[]>([])
  const [report, setReport] = useState('')
  const [history, setHistory] = useState<SessionSummary[]>([])
  const [viewing, setViewing] = useState<Session | null>(null)
  const reportRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    listSessions().then(setHistory).catch(() => {})
  }, [phase])

  const upsertStep = (s: Step) =>
    setSteps((prev) => {
      const i = prev.findIndex((p) => p.id === s.id)
      if (i === -1) return [...prev, s]
      const next = [...prev]
      next[i] = s
      return next
    })

  const start = async (t: string) => {
    if (!t.trim() || phase === 'running') return
    setTopic(t)
    setPhase('running')
    setError(null)
    setQuestions([])
    setSteps([])
    setTraces([])
    setReport('')
    setSessionId(null)
    setViewing(null)

    await startResearch(t.trim(), {
      onPlan: setQuestions,
      onStep: upsertStep,
      onTool: (ev) =>
        setTraces((prev) =>
          [...prev, { stepId: ev.stepId, text: `${ev.tool === 'web_search' ? '🔍' : '📄'} ${ev.input}` }].slice(-60),
        ),
      onReportToken: (token) => {
        setReport((prev) => {
          const next = prev + token
          reportRef.current?.scrollTo({ top: reportRef.current.scrollHeight })
          return next
        })
      },
      onDone: () => setPhase('done'),
      onError: (message) => {
        setError(message)
        setPhase('error')
      },
    })
  }

  const openSession = async (id: number) => {
    const s = await getSession(id)
    setViewing(s)
    setPhase('idle')
  }

  const runningSteps = steps.filter((s) => s.type === 'research')
  const doneCount = runningSteps.filter((s) => s.status !== 'running').length

  return (
    <div className="min-h-full">
      <header className="border-b border-stone-200 bg-white/70 backdrop-blur sticky top-0 z-10">
        <div className="max-w-5xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 rounded-lg bg-indigo-600 flex items-center justify-center text-sm font-bold text-white">D</div>
            <span className="font-semibold">DeepProbe</span>
            <span className="text-xs text-stone-400 hidden sm:inline">autonomous research agent</span>
          </div>
          {sessionId && phase === 'done' && (
            <a
              href={`/api/research/${sessionId}`}
              target="_blank"
              rel="noreferrer"
              className="text-xs text-stone-400 hover:text-stone-700"
            >
              raw JSON
            </a>
          )}
        </div>
      </header>

      <main className="max-w-5xl mx-auto px-4 py-8">
        {/* topic input */}
        <div className="bg-white border border-stone-200 rounded-2xl p-6 shadow-sm">
          <h1 className="text-xl font-semibold mb-1">What should I research?</h1>
          <p className="text-sm text-stone-500 mb-4">
            The agent plans sub-questions, searches the web, reads pages and writes a cited report.
          </p>
          <form
            onSubmit={(e) => { e.preventDefault(); start(topic) }}
            className="flex flex-col sm:flex-row gap-3"
          >
            <input
              value={topic}
              onChange={(e) => setTopic(e.target.value)}
              placeholder="e.g. 2026 年东南亚电动车市场格局"
              disabled={phase === 'running'}
              className="flex-1 border border-stone-300 rounded-xl px-4 py-2.5 text-sm placeholder:text-stone-400 focus:outline-none focus:border-indigo-500 disabled:opacity-50"
            />
            <button
              type="submit"
              disabled={phase === 'running' || !topic.trim()}
              className="bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 text-white rounded-xl px-6 py-2.5 text-sm font-medium transition-colors"
            >
              {phase === 'running' ? 'Researching…' : 'Research'}
            </button>
          </form>
          <div className="flex flex-wrap gap-2 mt-3">
            {EXAMPLES.map((ex) => (
              <button
                key={ex}
                onClick={() => setTopic(ex)}
                disabled={phase === 'running'}
                className="text-xs bg-stone-100 hover:bg-stone-200 rounded-full px-3 py-1 text-stone-600 transition-colors disabled:opacity-40"
              >
                {ex}
              </button>
            ))}
          </div>
        </div>

        {error && (
          <div className="mt-4 bg-rose-50 border border-rose-200 text-rose-700 rounded-xl px-4 py-3 text-sm fade-up">
            {error}
          </div>
        )}

        {/* live progress */}
        {(phase === 'running' || (phase === 'done' && steps.length > 0)) && !viewing && (
          <div className="mt-6 grid gap-4 lg:grid-cols-5 fade-up">
            <div className="lg:col-span-2 space-y-3">
              <h2 className="text-sm font-semibold text-stone-500 uppercase tracking-wide">
                Agent trace {runningSteps.length > 0 && `· ${doneCount}/${runningSteps.length}`}
              </h2>

              {questions.length > 0 && (
                <div className="bg-white border border-stone-200 rounded-xl p-4">
                  <p className="text-xs font-semibold text-stone-400 uppercase mb-2">Plan</p>
                  <ol className="text-sm space-y-1 list-decimal list-inside text-stone-700">
                    {questions.map((q) => <li key={q}>{q}</li>)}
                  </ol>
                </div>
              )}

              {steps.map((s) => (
                <div key={String(s.id)} className="bg-white border border-stone-200 rounded-xl p-4 fade-up">
                  <div className="flex items-start gap-2">
                    <StatusDot status={s.status} />
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium leading-snug break-words">{s.title}</p>
                      {s.type === 'research' && s.status === 'running' && (
                        <p className="text-xs text-stone-400 animate-pulse mt-0.5">searching & reading…</p>
                      )}
                      {s.sources.length > 0 && (
                        <p className="text-xs text-stone-400 mt-1">{s.sources.length} sources</p>
                      )}
                    </div>
                  </div>
                  {traces
                    .filter((t) => t.stepId === s.id)
                    .map((t, i) => (
                      <p key={i} className="text-xs text-stone-400 mt-1 ml-6 truncate">{t.text}</p>
                    ))}
                </div>
              ))}
            </div>

            <div className="lg:col-span-3">
              <div className="bg-white border border-stone-200 rounded-xl shadow-sm sticky top-20">
                <div className="border-b border-stone-100 px-4 py-2.5 flex items-center justify-between">
                  <p className="text-sm font-semibold text-stone-500 uppercase tracking-wide text-xs">Report</p>
                  {phase === 'done' && report && (
                    <button
                      onClick={() => downloadReport(report)}
                      className="text-xs text-indigo-600 hover:text-indigo-500"
                    >
                      download .md
                    </button>
                  )}
                </div>
                <div ref={reportRef} className="p-5 overflow-y-auto max-h-[65vh] report text-sm">
                  {report ? (
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>{report}</ReactMarkdown>
                  ) : (
                    <p className="text-stone-400 text-sm animate-pulse">
                      {phase === 'running' ? 'The agent is still researching — the report appears here once writing starts…' : 'No report'}
                    </p>
                  )}
                </div>
              </div>
            </div>
          </div>
        )}

        {/* past session view */}
        {viewing && (
          <div className="mt-6 space-y-4 fade-up">
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-semibold">{viewing.topic}</h2>
              <button onClick={() => setViewing(null)} className="text-sm text-stone-400 hover:text-stone-700">
                ← back
              </button>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              {viewing.steps.map((s) => (
                <div key={String(s.id)} className="bg-white border border-stone-200 rounded-xl p-4">
                  <div className="flex items-center gap-2 mb-1">
                    <StatusDot status={s.status} />
                    <p className="text-sm font-medium">{s.title}</p>
                  </div>
                  {s.summary && <p className="text-xs text-stone-500 line-clamp-4 mt-1">{s.summary}</p>}
                  {s.sources.length > 0 && (
                    <p className="text-xs text-stone-400 mt-1">{s.sources.length} sources</p>
                  )}
                </div>
              ))}
            </div>
            {viewing.reportMd && (
              <div className="bg-white border border-stone-200 rounded-xl p-6 report text-sm">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{viewing.reportMd}</ReactMarkdown>
              </div>
            )}
          </div>
        )}

        {/* history */}
        {history.length > 0 && !viewing && (
          <div className="mt-8">
            <h2 className="text-sm font-semibold text-stone-500 uppercase tracking-wide mb-2">Past research</h2>
            <div className="space-y-2">
              {history.map((h) => (
                <div key={h.id} className="bg-white border border-stone-200 rounded-xl px-4 py-3 flex items-center gap-3 fade-up">
                  <button onClick={() => openSession(h.id)} className="flex-1 text-left min-w-0">
                    <p className="text-sm font-medium truncate hover:text-indigo-600">{h.topic}</p>
                    <p className="text-xs text-stone-400">
                      {h.status} · {new Date(h.createdAt).toLocaleString()}
                    </p>
                  </button>
                  <button
                    onClick={async () => { await deleteSession(h.id); setHistory((p) => p.filter((x) => x.id !== h.id)) }}
                    className="text-xs text-stone-400 hover:text-rose-500"
                  >
                    delete
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}
      </main>
    </div>
  )
}

function StatusDot({ status }: { status: Step['status'] }) {
  if (status === 'done') {
    return <span className="w-2.5 h-2.5 rounded-full bg-emerald-500 shrink-0 mt-1.5" />
  }
  if (status === 'failed') {
    return <span className="w-2.5 h-2.5 rounded-full bg-rose-500 shrink-0 mt-1.5" />
  }
  return <span className="w-2.5 h-2.5 rounded-full bg-amber-400 animate-pulse shrink-0 mt-1.5" />
}

function downloadReport(md: string) {
  const blob = new Blob([md], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'research-report.md'
  a.click()
  URL.revokeObjectURL(url)
}
