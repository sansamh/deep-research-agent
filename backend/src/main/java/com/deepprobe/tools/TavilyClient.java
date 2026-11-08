package com.deepprobe.tools;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.deepprobe.config.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin client for the Tavily search API (https://tavily.com).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TavilyClient {

    private final AppProperties appProperties;

    private RestClient restClient() {
        return RestClient.builder()
            .baseUrl("https://api.tavily.com")
            .build();
    }

    public record SearchResult(String title, String url, String content) {
    }

    private record TavilyResponse(List<TavilyResult> results) {
        private record TavilyResult(String title, String url, String content) {
        }
    }

    public List<SearchResult> search(String query, int maxResults) {
        try {
            TavilyResponse resp = restClient().post()
                .uri("/search")
                .header("Authorization", "Bearer " + appProperties.getTavily().getApiKey())
                .header("Content-Type", "application/json")
                .body(new TavilyRequest(query, maxResults, true))
                .retrieve()
                .body(TavilyResponse.class);
            if (resp == null || resp.results() == null) {
                return List.of();
            }
            return resp.results().stream()
                .map(r -> new SearchResult(r.title(), r.url(), r.content()))
                .toList();
        } catch (Exception e) {
            log.warn("Tavily search failed for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private record TavilyRequest(String query, int max_results, boolean include_answer) {
    }
}
