# DeepProbe — Autonomous Deep Research Agent

Give it a topic. The agent **plans sub-questions, searches the web, reads pages, and writes a cited research report** — autonomously, with its full tool-use trace streamed live to the browser.

Live demo: **[deepprobe.pages.dev](https://deepprobe.pages.dev)** (frontend on Cloudflare Pages, backend on Render free tier — first request after idle takes ~1 min to wake up)

![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen) ![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-green) ![React](https://img.shields.io/badge/React-18-blue) ![TypeScript](https://img.shields.io/badge/TypeScript-5-blue)

## What makes it an agent

Not a fixed pipeline — the research phase is a genuine **tool-calling loop**. For every
sub-question, the LLM decides which tools to call and when it has enough material:

```
topic ──► Planner ──► 3-5 sub-questions
                         │  (parallel, Java 21 virtual threads)
                         ▼
              ┌─ ReAct research loop ──────────────┐
              │  LLM ⟷ web_search(query)           │
              │      ⟷ read_page(url)              │
              │  decides when evidence suffices    │
              └────────────┬───────────────────────┘
                           ▼
                  Writer ──► cited Markdown report
```

- **Tools as first-class functions** — `@Tool`-annotated Java methods (Spring AI function calling): web search via the Tavily API, page reading via Jsoup
- **Autonomy with guardrails** — max tool-call budget per question; the agent itself decides when to stop researching
- **Full traceability** — every tool call is streamed to the UI live (`🔍 query`, `📄 url`) and every report is backed by numbered citations
- **Concurrent research** — sub-questions are investigated in parallel on virtual threads

## Features

- Live SSE stream: plan → per-question progress with tool traces → streaming report
- Research history persisted (H2 locally / PostgreSQL in production)
- Reopen past reports, download as Markdown
- Multilingual: reports are written in the language of the topic

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Spring AI 1.0 (ChatClient, `@Tool` function calling), virtual threads, Jsoup, JPA |
| Search | Tavily Search API |
| Frontend | React 18, TypeScript, Vite, Tailwind CSS 4, react-markdown, native SSE streaming |
| Storage | H2 (dev) / PostgreSQL (prod) |

## Running locally

Prerequisites: Java 21, Node 18+, a Tavily API key (free at tavily.com) and any OpenAI-compatible LLM key.

**Backend** (from `backend/`):

```bash
export OPENAI_API_KEY=sk-...                  # any OpenAI-compatible key
export OPENAI_BASE_URL=https://api.openai.com # or e.g. https://api.siliconflow.cn
export LLM_MODEL=gpt-4o-mini                  # needs function-calling support
export TAVILY_API_KEY=tvly-...
mvn spring-boot:run
```

**Frontend** (from `frontend/`):

```bash
npm install
npm run dev        # http://localhost:5175, proxies /api to :8081
```

## API overview

| Method | Path | Description |
|---|---|---|
| POST | `/api/research` | topic → SSE stream (`session`, `plan`, `step`, `tool`, `report-token`, `done`) |
| GET | `/api/research` | research history |
| GET/DELETE | `/api/research/{id}` | fetch / delete a session (report + steps + sources) |

## Deployment

- **Backend** → Render free tier (root `Dockerfile`), PostgreSQL on **Neon** free tier
- **Frontend** → Cloudflare Pages with a small `_worker.js` proxying `/api/*` to the backend

Environment variables (backend): `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `LLM_MODEL`, `TAVILY_API_KEY`, `SPRING_PROFILES_ACTIVE=prod`, `JDBC_DATABASE_URL`, `CORS_ALLOWED_ORIGINS`.
