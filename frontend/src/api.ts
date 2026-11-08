import axios from 'axios'
import type { Session, SessionSummary, SourceInfo, Step, ToolEvent } from './types'

const api = axios.create({ baseURL: '/api' })

export async function listSessions(): Promise<SessionSummary[]> {
  const { data } = await api.get('/research')
  return data
}

export async function getSession(id: number): Promise<Session> {
  const { data } = await api.get(`/research/${id}`)
  return data
}

export async function deleteSession(id: number): Promise<void> {
  await api.delete(`/research/${id}`)
}

export interface StreamHandlers {
  onPlan: (questions: string[]) => void
  onStep: (step: Step) => void
  onTool: (ev: ToolEvent) => void
  onReportToken: (token: string) => void
  onDone: () => void
  onError: (message: string) => void
}

/** POSTs the topic and consumes the SSE research stream. */
export async function startResearch(topic: string, handlers: StreamHandlers): Promise<void> {
  let res: Response
  try {
    res = await fetch('/api/research', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ topic }),
    })
  } catch {
    handlers.onError('Network error — is the backend running?')
    return
  }

  if (!res.ok || !res.body) {
    let message = `Request failed (${res.status})`
    try {
      const body = await res.json()
      if (body?.message) message = body.message
    } catch { /* keep default */ }
    handlers.onError(message)
    return
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const dispatch = (eventName: string, data: string) => {
    if (!eventName) return
    switch (eventName) {
      case 'plan': {
        try {
          handlers.onPlan((JSON.parse(data) as { questions: string[] }).questions)
        } catch { /* ignore */ }
        break
      }
      case 'step':
        try {
          handlers.onStep(JSON.parse(data) as Step)
        } catch { /* ignore */ }
        break
      case 'tool':
        try {
          handlers.onTool(JSON.parse(data) as ToolEvent)
        } catch { /* ignore */ }
        break
      case 'report-token':
        handlers.onReportToken(data)
        break
      case 'done':
        handlers.onDone()
        break
      case 'error': {
        let message = 'Research failed'
        try {
          const parsed = JSON.parse(data)
          if (parsed?.message) message = parsed.message
        } catch { /* keep default */ }
        handlers.onError(message)
        break
      }
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let sep: number
    while ((sep = buffer.indexOf('\n\n')) !== -1) {
      const rawEvent = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      let eventName = ''
      const dataLines: string[] = []
      for (const line of rawEvent.split('\n')) {
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          // Spring SseEmitter writes "data:" + payload verbatim
          dataLines.push(line.slice(5))
        }
      }
      dispatch(eventName, dataLines.join('\n'))
    }
  }
  handlers.onDone()
}

export type { Session, SessionSummary, SourceInfo, Step, ToolEvent }
