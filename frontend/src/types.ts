export interface SourceInfo {
  index: number
  title: string
  url: string
}

export interface Step {
  id: number | string
  seq: number
  type: 'plan' | 'research' | 'write'
  title: string
  status: 'running' | 'done' | 'failed'
  summary: string | null
  sources: SourceInfo[]
}

export interface SessionSummary {
  id: number
  topic: string
  status: 'planning' | 'researching' | 'writing' | 'done' | 'failed'
  createdAt: string
}

export interface Session {
  id: number
  topic: string
  status: SessionSummary['status']
  reportMd: string | null
  createdAt: string
  steps: Step[]
}

export interface ToolEvent {
  stepId: number | string
  tool: 'web_search' | 'read_page'
  input: string
}
