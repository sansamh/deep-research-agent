package com.deepprobe.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Tavily tavily = new Tavily();
    private final Research research = new Research();
    private final Cors cors = new Cors();

    @Data
    public static class Tavily {
        private String apiKey;
    }

    @Data
    public static class Research {
        private int maxSubquestions = 5;
        private int maxToolIterations = 4;
        private int searchResultsPerQuery = 5;
        private int maxPageChars = 6000;
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173");
    }
}
