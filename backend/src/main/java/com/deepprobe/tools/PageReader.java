package com.deepprobe.tools;

import java.util.Locale;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import com.deepprobe.config.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches a web page and extracts readable text.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PageReader {

    private final AppProperties appProperties;

    public String read(String url) {
        try {
            if (!url.toLowerCase(Locale.ROOT).startsWith("http")) {
                return "Invalid URL";
            }
            Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; DeepProbeBot/1.0)")
                .timeout(10_000)
                .followRedirects(true)
                .maxBodySize(2_000_000)
                .get();
            doc.select("script, style, nav, header, footer, aside, iframe, noscript").remove();
            String text = doc.body() == null ? "" : doc.body().text();
            int max = appProperties.getResearch().getMaxPageChars();
            if (text.length() > max) {
                text = text.substring(0, max) + " ... [truncated]";
            }
            return text.isBlank() ? "No readable text found on this page" : text;
        } catch (Exception e) {
            log.warn("Could not read {}: {}", url, e.getMessage());
            return "Failed to read page: " + e.getMessage();
        }
    }
}
