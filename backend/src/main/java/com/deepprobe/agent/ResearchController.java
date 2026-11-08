package com.deepprobe.agent;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.deepprobe.agent.dto.ResearchDtos.SessionDto;
import com.deepprobe.agent.dto.ResearchDtos.SessionSummaryDto;
import com.deepprobe.agent.dto.ResearchDtos.SourceInfo;
import com.deepprobe.agent.dto.ResearchDtos.StartRequest;
import com.deepprobe.agent.dto.ResearchDtos.StepDto;
import com.deepprobe.research.ResearchSession;
import com.deepprobe.research.ResearchSessionRepository;
import com.deepprobe.research.ResearchStep;
import com.deepprobe.research.ResearchStepRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/research")
@RequiredArgsConstructor
@Slf4j
public class ResearchController {

    private static final long SSE_TIMEOUT = 15 * 60 * 1000L;

    private final ResearchSessionRepository sessions;
    private final ResearchStepRepository steps;
    private final ResearchAgentService agentService;
    private final ObjectMapper objectMapper;

    /**
     * Starts a research run and streams progress over SSE:
     * plan (JSON), step (JSON), tool (JSON), report-token, done, error.
     */
    @PostMapping
    public ResponseEntity<SseEmitter> start(@Valid @RequestBody StartRequest req) {
        ResearchSession session = sessions.save(ResearchSession.builder()
            .topic(req.topic())
            .status(ResearchSession.Status.PLANNING)
            .build());
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        agentService.run(session.getId(), emitter);
        return ResponseEntity.ok(emitter);
    }

    @GetMapping
    public List<SessionSummaryDto> list() {
        return sessions.findAllByOrderByCreatedAtDesc().stream()
            .map(s -> new SessionSummaryDto(s.getId(), s.getTopic(),
                s.getStatus().name().toLowerCase(), s.getCreatedAt().toString()))
            .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionDto> get(@PathVariable Long id) {
        var opt = sessions.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toSessionDto(opt.get()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!sessions.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        steps.deleteAll(steps.findBySessionIdOrderBySeqAsc(id));
        sessions.deleteById(id);
        return ResponseEntity.ok().build();
    }

    private SessionDto toSessionDto(ResearchSession s) {
        List<StepDto> stepDtos = steps.findBySessionIdOrderBySeqAsc(s.getId()).stream()
            .map(this::toStepDto)
            .toList();
        return new SessionDto(s.getId(), s.getTopic(), s.getStatus().name().toLowerCase(),
            s.getReportMd(), s.getCreatedAt().toString(), stepDtos);
    }

    private StepDto toStepDto(ResearchStep step) {
        List<SourceInfo> sources = List.of();
        if (step.getSourcesJson() != null) {
            try {
                sources = objectMapper.readValue(step.getSourcesJson(),
                    new TypeReference<List<SourceInfo>>() {});
            } catch (Exception e) {
                log.warn("Could not parse sources for step {}", step.getId());
            }
        }
        return new StepDto(step.getId(), step.getSeq(), step.getType().name().toLowerCase(),
            step.getTitle(), step.getStatus().name().toLowerCase(),
            step.getSummary(), sources);
    }
}
