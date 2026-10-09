package com.crispyland.rag;

import com.crispyland.rag.Day28Evaluator.Comparison;
import com.crispyland.rag.Day28Evaluator.EvalResults;
import com.crispyland.rag.Day28Evaluator.ProviderResult;
import com.crispyland.rag.Day28Evaluator.Summary;
import java.time.Instant;

/**
 * Generates a self-contained HTML report comparing cloud (Cerebras) vs local (Ollama) RAG answers.
 * Side-by-side layout with quality, speed, and stability metrics.
 */
public final class Day28ReportGenerator {

    private Day28ReportGenerator() {}

    public static String generate(EvalResults eval) {
        StringBuilder html = new StringBuilder();
        html.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                <title>Day 28 \u2014 Local LLM + RAG Comparison</title>
                <style>
                """);
        appendStyles(html);
        html.append("</style>\n</head>\n<body>\n");
        html.append("<div class=\"container\">\n");
        html.append("<h1>Day 28 \u2014 Local LLM + RAG Comparison</h1>\n");
        html.append("<p class=\"sub\">Same index, same retrieval, same prompts \u2014 different models</p>\n");
        html.append("<p class=\"timestamp\">Generated: ").append(Instant.now()).append("</p>\n");

        appendSummary(html, eval.summary());

        for (Comparison c : eval.comparisons()) {
            appendComparison(html, c);
        }

        html.append("</div>\n</body>\n</html>");
        return html.toString();
    }

    private static void appendStyles(StringBuilder html) {
        html.append("""
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    background: #0f172a; color: #e2e8f0; line-height: 1.6; padding: 2rem;
                }
                .container { max-width: 1200px; margin: 0 auto; }
                h1 { font-size: 24px; margin-bottom: 4px; }
                .sub { color: #94a3b8; font-size: 14px; margin-bottom: 4px; }
                .timestamp { color: #64748b; font-size: 12px; margin-bottom: 24px; }

                /* Summary cards */
                .summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                           gap: 12px; margin-bottom: 28px; }
                .scard { background: #1e293b; border: 1px solid #334155; border-radius: 10px;
                         padding: 16px; }
                .scard .label { font-size: 11px; text-transform: uppercase; letter-spacing: .05em;
                                color: #94a3b8; font-weight: 700; margin-bottom: 6px; }
                .scard .row { display: flex; justify-content: space-between; align-items: baseline;
                              margin-bottom: 4px; font-size: 14px; }
                .scard .val { font-weight: 700; font-variant-numeric: tabular-nums; }
                .scard .cloud { color: #60a5fa; }
                .scard .local { color: #6ee7b7; }
                .scard .winner { text-decoration: underline; text-underline-offset: 3px; }

                /* Comparison card */
                .comp { background: #1e293b; border: 1px solid #334155; border-radius: 10px;
                        margin-bottom: 20px; overflow: hidden; }
                .comp-head { padding: 14px 18px; border-bottom: 1px solid #334155;
                             display: flex; align-items: baseline; gap: 12px; }
                .comp-head .qnum { font-size: 12px; font-weight: 700; color: #94a3b8;
                                   background: #334155; border-radius: 999px; padding: 2px 10px; }
                .comp-head .topic { font-size: 11px; color: #94a3b8; text-transform: uppercase;
                                    letter-spacing: .04em; margin-left: auto; }
                .comp-head .question { font-size: 14px; font-weight: 600; }
                .comp-meta { padding: 8px 18px; font-size: 12px; color: #94a3b8;
                             border-bottom: 1px solid #334155; }

                /* Side-by-side answers */
                .sides { display: grid; grid-template-columns: 1fr 1fr; }
                .side { padding: 16px 18px; }
                .side + .side { border-left: 1px solid #334155; }
                .side-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
                .badge { font-size: 10px; font-weight: 700; padding: 2px 8px; border-radius: 999px;
                         text-transform: uppercase; letter-spacing: .04em; }
                .badge.cloud { background: #1e3a5f; color: #60a5fa; border: 1px solid #3b82f6; }
                .badge.local { background: #064e3b; color: #6ee7b7; border: 1px solid #059669; }
                .badge.error { background: #450a0a; color: #fca5a5; border: 1px solid #ef4444; }
                .side-head .model { font-size: 12px; color: #94a3b8; }
                .side-stats { font-size: 11px; color: #94a3b8; margin-bottom: 10px;
                              font-variant-numeric: tabular-nums; }
                .side-stats b { color: #cbd5e1; }
                .answer { font-size: 13px; white-space: pre-wrap; line-height: 1.55;
                          background: #0f172a; border-radius: 7px; padding: 12px;
                          max-height: 400px; overflow-y: auto; }
                .answer .section-title { font-weight: 700; font-size: 13px; color: #f8fafc;
                                         margin: 10px 0 4px; }
                .answer .section-title:first-child { margin-top: 0; }
                .sources-list { font-size: 12px; color: #94a3b8; margin-top: 8px; }
                .sources-list li { margin-bottom: 2px; }
                .quote-block { border-left: 3px solid #334155; padding: 4px 10px; margin: 4px 0;
                               font-size: 12px; color: #94a3b8; font-style: italic; }
                .quote-block.verified { border-color: #059669; }
                .quote-block.rejected { border-color: #ef4444; }

                /* Quality indicators */
                .quals { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 8px; }
                .qual { font-size: 10px; font-weight: 700; padding: 2px 7px; border-radius: 4px; }
                .qual.yes { background: #064e3b; color: #6ee7b7; border: 1px solid #059669; }
                .qual.no { background: #450a0a; color: #fca5a5; border: 1px solid #ef4444; }

                @media (max-width: 800px) {
                    .sides { grid-template-columns: 1fr; }
                    .side + .side { border-left: none; border-top: 1px solid #334155; }
                }
                """);
    }

    private static void appendSummary(StringBuilder html, Summary s) {
        html.append("<div class=\"summary\">\n");

        // Quality card
        html.append("<div class=\"scard\">\n");
        html.append("  <div class=\"label\">Quality \u2014 Sourced Answers</div>\n");
        appendMetricRow(html, "Cerebras", s.cloudSourced() + "/" + s.totalQueries(), "cloud",
                s.cloudSourced() >= s.localSourced());
        appendMetricRow(html, "Ollama", s.localSourced() + "/" + s.totalQueries(), "local",
                s.localSourced() >= s.cloudSourced());
        html.append("</div>\n");

        // Quotes card
        html.append("<div class=\"scard\">\n");
        html.append("  <div class=\"label\">Quality \u2014 Quoted Answers</div>\n");
        appendMetricRow(html, "Cerebras", s.cloudQuoted() + "/" + s.totalQueries(), "cloud",
                s.cloudQuoted() >= s.localQuoted());
        appendMetricRow(html, "Ollama", s.localQuoted() + "/" + s.totalQueries(), "local",
                s.localQuoted() >= s.cloudQuoted());
        html.append("</div>\n");

        // Speed card
        html.append("<div class=\"scard\">\n");
        html.append("  <div class=\"label\">Speed \u2014 Avg Latency</div>\n");
        appendMetricRow(html, "Cerebras", s.cloudAvgLatencyMs() + " ms", "cloud",
                s.cloudAvgLatencyMs() <= s.localAvgLatencyMs());
        appendMetricRow(html, "Ollama", s.localAvgLatencyMs() + " ms", "local",
                s.localAvgLatencyMs() <= s.cloudAvgLatencyMs());
        html.append("</div>\n");

        // Tokens card
        html.append("<div class=\"scard\">\n");
        html.append("  <div class=\"label\">Efficiency \u2014 Total Tokens</div>\n");
        appendMetricRow(html, "Cerebras", String.valueOf(s.cloudTotalTokens()), "cloud", false);
        appendMetricRow(html, "Ollama", String.valueOf(s.localTotalTokens()), "local", false);
        html.append("</div>\n");

        // Stability card
        html.append("<div class=\"scard\">\n");
        html.append("  <div class=\"label\">Stability \u2014 Errors</div>\n");
        appendMetricRow(html, "Cerebras", String.valueOf(s.cloudErrors()), "cloud",
                s.cloudErrors() <= s.localErrors());
        appendMetricRow(html, "Ollama", String.valueOf(s.localErrors()), "local",
                s.localErrors() <= s.cloudErrors());
        html.append("</div>\n");

        html.append("</div>\n");
    }

    private static void appendMetricRow(StringBuilder html, String label, String value,
                                         String css, boolean winner) {
        html.append("  <div class=\"row\"><span>").append(esc(label)).append("</span>");
        html.append("<span class=\"val ").append(css);
        if (winner) html.append(" winner");
        html.append("\">").append(esc(value)).append("</span></div>\n");
    }

    private static void appendComparison(StringBuilder html, Comparison c) {
        html.append("<div class=\"comp\">\n");

        // Header: question + topic
        html.append("  <div class=\"comp-head\">\n");
        html.append("    <span class=\"qnum\">Q").append(c.number()).append("</span>\n");
        html.append("    <span class=\"question\">").append(esc(c.question())).append("</span>\n");
        html.append("    <span class=\"topic\">").append(esc(c.topic())).append("</span>\n");
        html.append("  </div>\n");

        // Retrieval meta
        html.append("  <div class=\"comp-meta\">Retrieval: ");
        html.append(c.retrievedSources().size()).append(" chunks in ");
        html.append(c.ragLatencyMs()).append(" ms");
        if (!c.retrievedSources().isEmpty()) {
            html.append(" \u2014 ");
            for (int i = 0; i < c.retrievedSources().size(); i++) {
                if (i > 0) html.append(", ");
                html.append(esc(c.retrievedSources().get(i)));
            }
        }
        html.append("</div>\n");

        // Side-by-side answers
        html.append("  <div class=\"sides\">\n");
        appendSide(html, c.cloud());
        appendSide(html, c.local());
        html.append("  </div>\n");

        html.append("</div>\n");
    }

    private static void appendSide(StringBuilder html, ProviderResult r) {
        html.append("    <div class=\"side\">\n");

        // Provider badge + model
        html.append("      <div class=\"side-head\">\n");
        String badgeCss = "error".equals(r.finishReason()) ? "error" : r.badge();
        html.append("        <span class=\"badge ").append(badgeCss).append("\">")
            .append(esc(r.provider())).append("</span>\n");
        if (!r.model().isEmpty()) {
            html.append("        <span class=\"model\">").append(esc(r.model())).append("</span>\n");
        }
        html.append("      </div>\n");

        // Stats line
        html.append("      <div class=\"side-stats\">");
        html.append("<b>").append(r.answerLatencyMs()).append("</b> ms");
        if (r.answerUsage().totalTokens() > 0) {
            html.append(" \u00b7 prompt <b>").append(r.answerUsage().promptTokens()).append("</b>");
            html.append(" \u00b7 completion <b>").append(r.answerUsage().completionTokens()).append("</b>");
            html.append(" \u00b7 total <b>").append(r.answerUsage().totalTokens()).append("</b>");
        }
        if (!r.finishReason().isEmpty()) {
            html.append(" \u00b7 ").append(esc(r.finishReason()));
        }
        html.append("</div>\n");

        // Answer content
        html.append("      <div class=\"answer\">");
        if (r.parsedAnswer() != null) {
            appendParsedAnswer(html, r.parsedAnswer());
        } else {
            html.append(esc(r.rawAnswer()));
        }
        html.append("</div>\n");

        // Quality indicators
        html.append("      <div class=\"quals\">");
        html.append("<span class=\"qual ").append(r.hasSources() ? "yes" : "no").append("\">")
            .append(r.hasSources() ? "HAS SOURCES" : "NO SOURCES").append("</span>");
        html.append("<span class=\"qual ").append(r.hasQuotes() ? "yes" : "no").append("\">")
            .append(r.hasQuotes() ? "HAS QUOTES" : "NO QUOTES").append("</span>");
        if (r.quotesTotal() > 0) {
            html.append("<span class=\"qual ")
                .append(r.quotesVerified() > 0 ? "yes" : "no").append("\">")
                .append(r.quotesVerified()).append("/").append(r.quotesTotal())
                .append(" VERIFIED</span>");
        }
        html.append("</div>\n");

        html.append("    </div>\n");
    }

    private static void appendParsedAnswer(StringBuilder html, RagAnswer parsed) {
        // Answer section
        if (!parsed.answer().isBlank()) {
            html.append("<div class=\"section-title\">Answer</div>\n");
            html.append(esc(parsed.answer())).append("\n");
        }

        // Sources section
        if (!parsed.sources().isEmpty()) {
            html.append("<div class=\"section-title\">Sources</div>\n");
            html.append("<ul class=\"sources-list\">");
            for (RagAnswer.Source src : parsed.sources()) {
                html.append("<li>").append(esc(src.toString())).append("</li>");
            }
            html.append("</ul>\n");
        }

        // Quotes section
        if (!parsed.quotes().isEmpty()) {
            html.append("<div class=\"section-title\">Quotes</div>\n");
            for (RagAnswer.Quote q : parsed.quotes()) {
                String cls = q.status() == RagAnswer.QuoteStatus.VERIFIED ? "verified" : "rejected";
                html.append("<div class=\"quote-block ").append(cls).append("\">");
                html.append("\"").append(esc(q.text())).append("\"");
                if (!q.sourceRef().isEmpty()) {
                    html.append(" \u2014 ").append(esc(q.sourceRef()));
                }
                html.append("</div>\n");
            }
        }
    }

    private static String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;")
                   .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
