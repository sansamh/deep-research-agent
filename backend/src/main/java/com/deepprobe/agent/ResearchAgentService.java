package com.deepprobe.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.deepprobe.agent.dto.ResearchDtos;
import com.deepprobe.agent.dto.ResearchDtos.SourceInfo;
import com.deepprobe.config.AppProperties;
import com.deepprobe.research.ResearchSession;
import com.deepprobe.research.ResearchSessionRepository;
import com.deepprobe.research.ResearchStep;
import com.deepprobe.research.ResearchStepRepository;
import com.deepprobe.tools.PageReader;
import com.deepprobe.tools.TavilyClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ResearchAgentService {

    private final ChatClient chatClient;
    private final TavilyClient tavily;
    private final PageReader pageReader;
    private final ResearchSessionRepository sessions;
    private final ResearchStepRepository steps;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ResearchAgentService(ChatClient.Builder chatClientBuilder, TavilyClient tavily,
                                PageReader pageReader, ResearchSessionRepository sessions,
                                ResearchStepRepository steps, AppProperties appProperties,
                                ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.tavily = tavily;
        this.pageReader = pageReader;
        this.sessions = sessions;
        this.steps = steps;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    private static final String PLANNER_PROMPT = """
        You are a research planner. Break the research topic below into %d focused, \
        non-overlapping sub-questions that together cover the topic well \
        (market data, key players, trends, risks, outlook - as appropriate).

        Respond with ONLY a JSON array of question strings, no other text.
        Write the questions in the same language as the topic.

        Topic: %s
        """;

    private static final String RESEARCHER_PROMPT = """
        You are a meticulous research assistant. You are working on ONE question as part \
        of a larger research project.

        You have two tools:
        - web_search(query): search the web; returns numbered results with title, URL and snippet.
        - read_page(url): fetch the full readable text of a page.

        Strategy: start with 1-2 searches, then read the most promising pages. If key \
        information is missing or conflicting, run one more targeted search. Use at most \
        %d tool calls in total.

        When you have enough material, stop calling tools and write your final answer:
        - A factual summary of 200-400 words that directly answers the question.
        - Cite sources inline as [n] where n is the source number shown by the tools.
        - End with a "Sources:" list of ONLY the numbered sources you actually used.
        - Write in the same language as the question.
        """;

    private static final String WRITER_PROMPT = """
        You are a senior analyst writing the final research report. You will receive \
        research summaries for several sub-questions of one topic, each with its own \
        local source numbering.

        Write a well-structured Markdown report:
        - A concise title (#) and a 3-5 sentence executive summary.
        - Thematic sections (##) that synthesise across the summaries - do NOT simply \
        repeat them question by question.
        - Keep inline citations in [n] form, but renumber ALL sources into one global, \
        sequential numbering, and end with a consolidated "## Sources" list \
        (format: `[n] title - URL`).
        - Note any conflicting information you could not resolve.
        - Write in the same language as the research material.
        """;

    public void run(Long sessionId, SseEmitter emitter) {
        executor.submit(() -> {
            try {
                doRun(sessionId, emitter);
            } catch (Exception e) {
                log.error("Research {} failed", sessionId, e);
                fail(sessionId, rootMessage(e));
                send(emitter, "error", json(Map.of("message", rootMessage(e))));
                emitter.completeWithError(e);
            }
        });
    }

    private void doRun(Long sessionId, SseEmitter emitter) {
        ResearchSession session = sessions.findById(sessionId).orElseThrow();
        send(emitter, "session", json(Map.of("id", sessionId)));

        // ---- Phase 1: plan ----
        session.setStatus(ResearchSession.Status.PLANNING);
        sessions.save(session);
        List<String> questions = planQuestions(session.getTopic());
        ResearchStep planStep = saveStep(session, 0, ResearchStep.Type.PLAN,
            "研究规划 / Planning", ResearchStep.Status.DONE, String.join("\n", questions), null);
        send(emitter, "plan", json(Map.of("questions", questions)));
        sendStep(emitter, planStep);
        log.info("Session {}: planned {} sub-questions", sessionId, questions.size());

        // ---- Phase 2: parallel research ----
        session.setStatus(ResearchSession.Status.RESEARCHING);
        sessions.save(session);
        List<ResearchStep> researchSteps = new ArrayList<>();
        List<Thread> workers = new ArrayList<>();
        AtomicInteger seqCounter = new AtomicInteger(1);

        for (String question : questions) {
            int seq = seqCounter.getAndIncrement();
            ResearchStep step = saveStep(session, seq, ResearchStep.Type.RESEARCH,
                question, ResearchStep.Status.RUNNING, null, null);
            researchSteps.add(step);
            sendStep(emitter, step);
            workers.add(Thread.ofVirtual().name("research-" + seq).start(() ->
                researchOne(emitter, session, step, question)));
        }
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Session {}: research phase done", sessionId);

        // ---- Phase 3: write report ----
        session.setStatus(ResearchSession.Status.WRITING);
        sessions.save(session);
        ResearchStep writeStep = saveStep(session, seqCounter.getAndIncrement(),
            ResearchStep.Type.WRITE, "撰写报告 / Writing report", ResearchStep.Status.RUNNING, null, null);
        sendStep(emitter, writeStep);

        String report = writeReport(session.getTopic(), researchSteps, token ->
            send(emitter, "report-token", token));
        writeStep.setStatus(ResearchStep.Status.DONE);
        writeStep.setSummary("Report written (" + report.length() + " chars)");
        steps.save(writeStep);
        sendStep(emitter, writeStep);

        session.setStatus(ResearchSession.Status.DONE);
        session.setReportMd(report);
        sessions.save(session);
        send(emitter, "done", "{}");
        emitter.complete();
        log.info("Session {}: done, report {} chars", sessionId, report.length());
    }

    // ---------- phase implementations ----------

    private List<String> planQuestions(String topic) {
        int max = appProperties.getResearch().getMaxSubquestions();
        String raw = chatClient.prompt()
            .user(PLANNER_PROMPT.formatted(max, topic))
            .call()
            .content();
        try {
            int start = raw.indexOf('[');
            int end = raw.lastIndexOf(']');
            if (start < 0 || end <= start) {
                throw new IllegalStateException("no JSON array in planner output");
            }
            List<String> questions = objectMapper.readValue(raw.substring(start, end + 1),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            if (questions.isEmpty()) {
                throw new IllegalStateException("empty plan");
            }
            return questions.stream().limit(max).toList();
        } catch (Exception e) {
            log.warn("Planner output could not be parsed, falling back to lines: {}", raw);
            return List.of(topic);
        }
    }

    private void researchOne(SseEmitter emitter, ResearchSession session,
                             ResearchStep step, String question) {
        ResearchContext ctx = new ResearchContext();
        try {
            ResearchTools tools = new ResearchTools(ctx, step.getId(), emitter);
            String summary = chatClient.prompt()
                .system(RESEARCHER_PROMPT.formatted(appProperties.getResearch().getMaxToolIterations()))
                .user(question)
                .tools(tools)
                .call()
                .content();

            step.setStatus(ResearchStep.Status.DONE);
            step.setSummary(summary);
            step.setSourcesJson(json(ctx.sources()));
            steps.save(step);
            sendStep(emitter, step);
        } catch (Exception e) {
            log.warn("Research step {} failed: {}", step.getId(), rootMessage(e));
            step.setStatus(ResearchStep.Status.FAILED);
            step.setSummary("Failed: " + rootMessage(e));
            steps.save(step);
            sendStep(emitter, step);
        }
    }

    private String writeReport(String topic, List<ResearchStep> researchSteps,
                               java.util.function.Consumer<String> tokenSink) {
        StringBuilder material = new StringBuilder();
        int global = 0;
        for (ResearchStep step : researchSteps) {
            if (step.getStatus() != ResearchStep.Status.DONE || step.getSummary() == null) {
                continue;
            }
            material.append("## Question: ").append(step.getTitle()).append("\n\n")
                .append(step.getSummary()).append("\n\n");
            global++;
        }
        return chatClient.prompt()
            .system(WRITER_PROMPT)
            .user("Research topic: " + topic + "\n\nResearch material:\n\n" + material)
            .stream()
            .content()
            .doOnNext(tokenSink::accept)
            .collectList()
            .map(list -> String.join("", list))
            .block();
    }

    // ---------- inner tools (called by the LLM) ----------

    class ResearchTools {

        private final ResearchContext ctx;
        private final Long stepId;
        private final SseEmitter emitter;

        ResearchTools(ResearchContext ctx, Long stepId, SseEmitter emitter) {
            this.ctx = ctx;
            this.stepId = stepId;
            this.emitter = emitter;
        }

        @Tool(description = "Search the web. Returns a numbered list of results " +
            "with title, URL and a content snippet. Source numbers can be cited as [n].")
        public String webSearch(@ToolParam(description = "The search query") String query) {
            var results = tavily.search(query, appProperties.getResearch().getSearchResultsPerQuery());
            if (results.isEmpty()) {
                return "No results found for: " + query;
            }
            StringBuilder sb = new StringBuilder();
            for (var r : results) {
                int n = ctx.add(r.title(), r.url());
                String snippet = r.content() == null ? "" : r.content();
                if (snippet.length() > 1000) {
                    snippet = snippet.substring(0, 1000) + " ...";
                }
                sb.append("[").append(n).append("] ").append(r.title()).append("\n")
                    .append("URL: ").append(r.url()).append("\n")
                    .append(snippet).append("\n\n");
            }
            send(emitter, "tool", json(Map.of("stepId", stepId, "tool", "web_search", "input", query)));
            return sb.toString();
        }

        @Tool(description = "Fetch and read the main text content of a web page. " +
            "Use it on URLs returned by web_search that look most relevant.")
        public String readPage(@ToolParam(description = "The page URL to read") String url) {
            String text = pageReader.read(url);
            ctx.add(url, url);
            send(emitter, "tool", json(Map.of("stepId", stepId, "tool", "read_page", "input", url)));
            return text;
        }
    }

    /** Numbered source registry shared by the tool calls of one research question. */
    static class ResearchContext {
        private final Map<String, SourceInfo> byUrl = new LinkedHashMap<>();
        private final AtomicInteger counter = new AtomicInteger(0);

        synchronized int add(String title, String url) {
            SourceInfo existing = byUrl.get(url);
            if (existing != null) {
                return existing.index();
            }
            int n = counter.incrementAndGet();
            byUrl.put(url, new SourceInfo(n, title, url));
            return n;
        }

        synchronized List<SourceInfo> sources() {
            return List.copyOf(byUrl.values());
        }
    }

    // ---------- helpers ----------

    private ResearchStep saveStep(ResearchSession session, int seq, ResearchStep.Type type,
                                  String title, ResearchStep.Status status, String summary,
                                  String sourcesJson) {
        ResearchStep step = steps.save(ResearchStep.builder()
            .session(session)
            .seq(seq)
            .type(type)
            .title(truncate(title, 480))
            .status(status)
            .summary(summary)
            .sourcesJson(sourcesJson)
            .build());
        return step;
    }

    private void fail(Long sessionId, String message) {
        sessions.findById(sessionId).ifPresent(s -> {
            s.setStatus(ResearchSession.Status.FAILED);
            s.setErrorMessage(truncate(message, 900));
            sessions.save(s);
        });
    }

    private void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data == null ? "" : data));
        } catch (Exception e) {
            // client disconnected - keep researching, history is persisted
        }
    }

    private void sendStep(SseEmitter emitter, ResearchStep step) {
        try {
            String payload = objectMapper.writeValueAsString(new ResearchDtos.StepDto(
                step.getId(), step.getSeq(), step.getType().name().toLowerCase(),
                step.getTitle(), step.getStatus().name().toLowerCase(),
                truncate(step.getSummary(), 4000), List.of()));
            send(emitter, "step", payload);
        } catch (Exception ignored) {
        }
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? "Unknown error" : msg;
    }
}
