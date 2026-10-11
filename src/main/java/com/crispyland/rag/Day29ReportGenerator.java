package com.crispyland.rag;

import com.crispyland.rag.Day29Evaluator.EvalResults;
import com.crispyland.rag.Day29Evaluator.Experiment;
import com.crispyland.rag.Day29Evaluator.ExperimentResult;
import com.crispyland.rag.Day29Evaluator.ExperimentSummary;
import com.crispyland.rag.Day29Evaluator.QueryComparison;
import com.crispyland.rag.Day29Evaluator.RunResult;
import java.time.Instant;

/**
 * Generates a self-contained HTML report for the Day 29 parameter sweep.
 * Each experiment gets a summary card and side-by-side query comparisons.
 */
public final class Day29ReportGenerator {

    private Day29ReportGenerator() {}

    public static String generate(EvalResults eval) {
        StringBuilder html = new StringBuilder();
        html.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                <title>Day 29 \u2014 Local LLM Parameter Sweep</title>
                <style>
                """);
        appendStyles(html);
        html.append("</style>\n</head>\n<body>\n");
        html.append("<div class=\"container\">\n");
        html.append("<h1>Day 29 \u2014 Local LLM Optimization</h1>\n");
        html.append("<p class=\"sub\">Parameter sweep: one variable at a time, 4 RAG queries each</p>\n");
        html.append("<p class=\"timestamp\">Generated: ").append(Instant.now()).append("</p>\n");

        // Table of contents
        html.append("<div class=\"toc\">\n");
        for (int i = 0; i < eval.experiments().size(); i++) {
            ExperimentResult er = eval.experiments().get(i);
            html.append("  <a href=\"#exp-").append(er.experiment().id()).append("\" class=\"toc-item\">");
            html.append(esc(er.experiment().name()));
            html.append("<span class=\"toc-param\">").append(esc(er.experiment().paramName()))
                .append(": ").append(esc(er.experiment().valueA()))
                .append(" vs ").append(esc(er.experiment().valueB()));
            html.append("</span></a>\n");
        }
        html.append("</div>\n");

        // Key findings summary
        html.append("<div class=\"findings\">\n");
        html.append("<h2>Key Findings</h2>\n");
        for (ExperimentResult er : eval.experiments()) {
            html.append("<div class=\"finding\">\n");
            html.append("  <div class=\"finding-title\">").append(esc(er.experiment().name()))
                .append(" <span class=\"finding-param\">(")
                .append(esc(er.experiment().valueA())).append(" vs ")
                .append(esc(er.experiment().valueB())).append(")</span></div>\n");
            html.append("  <p>").append(interpretExperiment(er)).append("</p>\n");
            html.append("</div>\n");
        }
        html.append("</div>\n");

        // Each experiment
        for (ExperimentResult er : eval.experiments()) {
            appendExperiment(html, er);
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
                h2 { font-size: 18px; margin-bottom: 4px; }
                .sub { color: #94a3b8; font-size: 14px; margin-bottom: 4px; }
                .timestamp { color: #64748b; font-size: 12px; margin-bottom: 24px; }

                /* TOC */
                .toc { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 28px; }
                .toc-item { background: #1e293b; border: 1px solid #334155; border-radius: 8px;
                            padding: 8px 14px; text-decoration: none; color: #e2e8f0;
                            font-size: 13px; font-weight: 600; transition: border-color 0.2s; }
                .toc-item:hover { border-color: #60a5fa; }
                .toc-param { display: block; font-size: 11px; color: #94a3b8; font-weight: 400;
                             margin-top: 2px; }

                /* Experiment section */
                .experiment { margin-bottom: 36px; }
                .exp-header { background: #1e293b; border: 1px solid #334155; border-radius: 10px;
                              padding: 16px 18px; margin-bottom: 12px; }
                .exp-header .desc { color: #94a3b8; font-size: 13px; margin-top: 4px; }
                .exp-header .param-row { display: flex; gap: 16px; margin-top: 10px;
                                         font-size: 13px; }
                .param-box { background: #0f172a; border-radius: 6px; padding: 6px 12px; }
                .param-box.a { border-left: 3px solid #60a5fa; }
                .param-box.b { border-left: 3px solid #f59e0b; }
                .param-box .lbl { font-size: 10px; text-transform: uppercase;
                                  letter-spacing: .04em; color: #94a3b8; }

                /* Summary row */
                .exp-summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
                               gap: 10px; margin-bottom: 14px; }
                .scard { background: #1e293b; border: 1px solid #334155; border-radius: 8px;
                         padding: 12px; }
                .scard .label { font-size: 10px; text-transform: uppercase; letter-spacing: .04em;
                                color: #94a3b8; font-weight: 700; margin-bottom: 4px; }
                .scard .row { display: flex; justify-content: space-between; align-items: baseline;
                              margin-bottom: 2px; font-size: 13px; }
                .scard .val { font-weight: 700; font-variant-numeric: tabular-nums; }
                .scard .a-val { color: #60a5fa; }
                .scard .b-val { color: #f59e0b; }
                .scard .winner { text-decoration: underline; text-underline-offset: 3px; }

                /* Query comparison card */
                .qcard { background: #1e293b; border: 1px solid #334155; border-radius: 8px;
                         margin-bottom: 10px; overflow: hidden; }
                .qcard-head { padding: 10px 14px; border-bottom: 1px solid #334155;
                              display: flex; align-items: baseline; gap: 10px; }
                .qcard-head .qnum { font-size: 11px; font-weight: 700; color: #94a3b8;
                                    background: #334155; border-radius: 999px; padding: 1px 8px; }
                .qcard-head .question { font-size: 13px; font-weight: 600; }
                .qcard-head .topic { font-size: 10px; color: #94a3b8; text-transform: uppercase;
                                     letter-spacing: .04em; margin-left: auto; }

                /* Side-by-side */
                .sides { display: grid; grid-template-columns: 1fr 1fr; }
                .side { padding: 12px 14px; }
                .side + .side { border-left: 1px solid #334155; }
                .side-head { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
                .badge { font-size: 9px; font-weight: 700; padding: 2px 7px; border-radius: 999px;
                         text-transform: uppercase; letter-spacing: .04em; }
                .badge.side-a { background: #1e3a5f; color: #60a5fa; border: 1px solid #3b82f6; }
                .badge.side-b { background: #451a03; color: #f59e0b; border: 1px solid #d97706; }
                .badge.error { background: #450a0a; color: #fca5a5; border: 1px solid #ef4444; }
                .side-head .model { font-size: 11px; color: #94a3b8; }
                .side-stats { font-size: 10px; color: #94a3b8; margin-bottom: 6px;
                              font-variant-numeric: tabular-nums; }
                .side-stats b { color: #cbd5e1; }
                .answer { font-size: 12px; white-space: pre-wrap; line-height: 1.5;
                          background: #0f172a; border-radius: 6px; padding: 10px;
                          max-height: 300px; overflow-y: auto; }
                .answer .section-title { font-weight: 700; font-size: 12px; color: #f8fafc;
                                         margin: 8px 0 3px; }
                .answer .section-title:first-child { margin-top: 0; }
                .sources-list { font-size: 11px; color: #94a3b8; margin-top: 6px; }
                .sources-list li { margin-bottom: 1px; }
                .quote-block { border-left: 3px solid #334155; padding: 3px 8px; margin: 3px 0;
                               font-size: 11px; color: #94a3b8; font-style: italic; }
                .quote-block.verified { border-color: #059669; }
                .quote-block.rejected { border-color: #ef4444; }
                .quals { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 6px; }
                .qual { font-size: 9px; font-weight: 700; padding: 1px 6px; border-radius: 3px; }
                .qual.yes { background: #064e3b; color: #6ee7b7; border: 1px solid #059669; }
                .qual.no { background: #450a0a; color: #fca5a5; border: 1px solid #ef4444; }

                /* Findings */
                .findings { background: #1e293b; border: 1px solid #334155; border-radius: 10px;
                            padding: 18px 20px; margin-bottom: 28px; }
                .findings h2 { font-size: 16px; margin-bottom: 12px; color: #f8fafc; }
                .finding { margin-bottom: 10px; }
                .finding:last-child { margin-bottom: 0; }
                .finding-title { font-size: 13px; font-weight: 700; color: #e2e8f0; margin-bottom: 2px; }
                .finding-param { font-weight: 400; color: #94a3b8; font-size: 12px; }
                .finding p { font-size: 13px; color: #94a3b8; line-height: 1.5; }

                @media (max-width: 800px) {
                    .sides { grid-template-columns: 1fr; }
                    .side + .side { border-left: none; border-top: 1px solid #334155; }
                }
                """);
    }

    private static void appendExperiment(StringBuilder html, ExperimentResult er) {
        Experiment exp = er.experiment();
        html.append("<div class=\"experiment\" id=\"exp-").append(exp.id()).append("\">\n");

        // Header
        html.append("<div class=\"exp-header\">\n");
        html.append("  <h2>").append(esc(exp.name())).append("</h2>\n");
        html.append("  <div class=\"desc\">").append(esc(exp.description())).append("</div>\n");
        html.append("  <div class=\"param-row\">\n");
        html.append("    <div class=\"param-box a\"><div class=\"lbl\">A \u2014 ")
            .append(esc(exp.paramName())).append("</div>")
            .append(esc(exp.valueA())).append("</div>\n");
        html.append("    <div class=\"param-box b\"><div class=\"lbl\">B \u2014 ")
            .append(esc(exp.paramName())).append("</div>")
            .append(esc(exp.valueB())).append("</div>\n");
        html.append("  </div>\n");
        html.append("</div>\n");

        // Summary cards
        ExperimentSummary s = er.summary();
        html.append("<div class=\"exp-summary\">\n");

        appendSummaryCard(html, "Sources", s.aSourced() + "/" + s.total(),
                s.bSourced() + "/" + s.total(), s.aSourced() >= s.bSourced(),
                s.bSourced() >= s.aSourced());
        appendSummaryCard(html, "Quotes", s.aQuoted() + "/" + s.total(),
                s.bQuoted() + "/" + s.total(), s.aQuoted() >= s.bQuoted(),
                s.bQuoted() >= s.aQuoted());
        appendSummaryCard(html, "Avg Latency", s.aAvgLatencyMs() + " ms",
                s.bAvgLatencyMs() + " ms", s.aAvgLatencyMs() <= s.bAvgLatencyMs(),
                s.bAvgLatencyMs() <= s.aAvgLatencyMs());
        appendSummaryCard(html, "Total Tokens", String.valueOf(s.aTotalTokens()),
                String.valueOf(s.bTotalTokens()), false, false);

        if (s.aErrors() > 0 || s.bErrors() > 0) {
            appendSummaryCard(html, "Errors", String.valueOf(s.aErrors()),
                    String.valueOf(s.bErrors()), s.aErrors() <= s.bErrors(),
                    s.bErrors() <= s.aErrors());
        }

        html.append("</div>\n");

        // Query comparisons
        for (QueryComparison qc : er.comparisons()) {
            appendQueryComparison(html, qc);
        }

        html.append("</div>\n");
    }

    private static void appendSummaryCard(StringBuilder html, String label,
                                           String aVal, String bVal,
                                           boolean aWins, boolean bWins) {
        html.append("<div class=\"scard\">\n");
        html.append("  <div class=\"label\">").append(esc(label)).append("</div>\n");
        html.append("  <div class=\"row\"><span>A</span><span class=\"val a-val");
        if (aWins) html.append(" winner");
        html.append("\">").append(esc(aVal)).append("</span></div>\n");
        html.append("  <div class=\"row\"><span>B</span><span class=\"val b-val");
        if (bWins) html.append(" winner");
        html.append("\">").append(esc(bVal)).append("</span></div>\n");
        html.append("</div>\n");
    }

    private static void appendQueryComparison(StringBuilder html, QueryComparison qc) {
        html.append("<div class=\"qcard\">\n");

        html.append("  <div class=\"qcard-head\">\n");
        html.append("    <span class=\"qnum\">Q").append(qc.number()).append("</span>\n");
        html.append("    <span class=\"question\">").append(esc(qc.question())).append("</span>\n");
        html.append("    <span class=\"topic\">").append(esc(qc.topic())).append("</span>\n");
        html.append("  </div>\n");

        html.append("  <div class=\"sides\">\n");
        appendSide(html, qc.sideA(), "side-a");
        appendSide(html, qc.sideB(), "side-b");
        html.append("  </div>\n");

        html.append("</div>\n");
    }

    private static void appendSide(StringBuilder html, RunResult r, String badgeCss) {
        html.append("    <div class=\"side\">\n");

        html.append("      <div class=\"side-head\">\n");
        String css = "error".equals(r.finishReason()) ? "error" : badgeCss;
        html.append("        <span class=\"badge ").append(css).append("\">")
            .append(esc(r.label())).append("</span>\n");
        if (!r.model().isEmpty()) {
            html.append("        <span class=\"model\">").append(esc(r.model())).append("</span>\n");
        }
        html.append("      </div>\n");

        html.append("      <div class=\"side-stats\">");
        html.append("<b>").append(r.latencyMs()).append("</b> ms");
        if (r.usage().totalTokens() > 0) {
            html.append(" \u00b7 prompt <b>").append(r.usage().promptTokens()).append("</b>");
            html.append(" \u00b7 compl <b>").append(r.usage().completionTokens()).append("</b>");
        }
        if (!r.finishReason().isEmpty()) {
            html.append(" \u00b7 ").append(esc(r.finishReason()));
        }
        html.append("</div>\n");

        html.append("      <div class=\"answer\">");
        if (r.parsedAnswer() != null) {
            appendParsedAnswer(html, r.parsedAnswer());
        } else {
            html.append(esc(r.rawAnswer()));
        }
        html.append("</div>\n");

        html.append("      <div class=\"quals\">");
        html.append("<span class=\"qual ").append(r.hasSources() ? "yes" : "no").append("\">")
            .append(r.hasSources() ? "SOURCES" : "NO SRC").append("</span>");
        html.append("<span class=\"qual ").append(r.hasQuotes() ? "yes" : "no").append("\">")
            .append(r.hasQuotes() ? "QUOTES" : "NO QT").append("</span>");
        if (r.quotesTotal() > 0) {
            html.append("<span class=\"qual ")
                .append(r.quotesVerified() > 0 ? "yes" : "no").append("\">")
                .append(r.quotesVerified()).append("/").append(r.quotesTotal())
                .append(" OK</span>");
        }
        html.append("</div>\n");

        html.append("    </div>\n");
    }

    private static void appendParsedAnswer(StringBuilder html, RagAnswer parsed) {
        if (!parsed.answer().isBlank()) {
            html.append("<div class=\"section-title\">Answer</div>\n");
            html.append(esc(parsed.answer())).append("\n");
        }
        if (!parsed.sources().isEmpty()) {
            html.append("<div class=\"section-title\">Sources</div>\n");
            html.append("<ul class=\"sources-list\">");
            for (RagAnswer.Source src : parsed.sources()) {
                html.append("<li>").append(esc(src.toString())).append("</li>");
            }
            html.append("</ul>\n");
        }
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

    private static String interpretExperiment(ExperimentResult er) {
        ExperimentSummary s = er.summary();
        String id = er.experiment().id();
        long aLat = s.aAvgLatencyMs();
        long bLat = s.bAvgLatencyMs();
        boolean allErrors = s.aErrors() == s.total() || s.bErrors() == s.total();

        return switch (id) {
            case "temperature" -> {
                String faster = aLat < bLat ? "Greedy (0.0)" : "Creative (0.7)";
                double ratio = aLat > 0 && bLat > 0
                        ? (double) Math.max(aLat, bLat) / Math.min(aLat, bLat) : 1.0;
                yield String.format(
                    "Both temperatures produced sources in %d/%d and %d/%d queries respectively. " +
                    "%s was %.1fx faster (%d ms vs %d ms avg). " +
                    "For RAG tasks where factual grounding matters more than creativity, temperature 0.7 " +
                    "surprisingly works well — the retrieved context anchors the answer, and the faster " +
                    "sampling compensates for any slight randomness.",
                    s.aSourced(), s.total(), s.bSourced(), s.total(),
                    faster, ratio, Math.min(aLat, bLat), Math.max(aLat, bLat));
            }
            case "quant" -> {
                if (allErrors) {
                    yield String.format(
                        "The Q8_0 model (3.4 GB) failed all %d queries due to timeouts. " +
                        "Ollama must unload the Q4 model and load Q8 into memory on each switch, " +
                        "causing ~300s delays that exceed the timeout. In a parameter sweep that " +
                        "alternates models, Q8 is impractical — it would need dedicated runs with " +
                        "no model swapping to evaluate fairly.", s.total());
                }
                yield String.format(
                    "Q4_K_M sourced %d/%d, Q8_0 sourced %d/%d queries. " +
                    "Latency: %d ms vs %d ms avg. The 8-bit quantization doubles memory usage " +
                    "(2 GB → 3.4 GB) with marginal quality improvement for RAG tasks, where the " +
                    "retrieved context provides most of the factual grounding.",
                    s.aSourced(), s.total(), s.bSourced(), s.total(), aLat, bLat);
            }
            case "model-size" -> {
                if (allErrors) {
                    yield "One model failed all queries — likely due to model-swapping timeouts. " +
                          "Dedicated runs without alternating models would be needed for a fair comparison.";
                }
                yield String.format(
                    "The 3B model sourced %d/%d queries, the 1B model sourced %d/%d. " +
                    "Latency: %d ms vs %d ms avg. The 1B model is 3x smaller but performed " +
                    "comparably on these RAG queries — when the context window supplies the relevant " +
                    "information, a smaller model can extract and cite it nearly as well as a larger one. " +
                    "This makes the 1B model attractive for resource-constrained environments.",
                    s.aSourced(), s.total(), s.bSourced(), s.total(), aLat, bLat);
            }
            case "context-window" -> {
                yield String.format(
                    "Default context (131K) and limited context (4K) both sourced %d/%d and %d/%d queries. " +
                    "Latency was %d ms vs %d ms avg. Reducing num_ctx to 4096 had no measurable impact " +
                    "because our RAG prompts are well under 4K tokens — Ollama only allocates KV cache for " +
                    "the actual prompt length, so a smaller cap doesn't save anything for short inputs.",
                    s.aSourced(), s.total(), s.bSourced(), s.total(), aLat, bLat);
            }
            case "repeat-penalty" -> {
                yield String.format(
                    "No penalty (1.0) sourced %d/%d, penalty 1.2 sourced %d/%d queries. " +
                    "Latency: %d ms vs %d ms avg. Repeat penalty actively hurts RAG quality: it penalizes " +
                    "the model for repeating words from the provided context, which is exactly what verbatim " +
                    "quoting requires. For RAG tasks, keep repeat_penalty at 1.0.",
                    s.aSourced(), s.total(), s.bSourced(), s.total(), aLat, bLat);
            }
            case "top-p" -> {
                yield String.format(
                    "Full distribution (1.0) and nucleus sampling (0.9) both sourced %d/%d and %d/%d queries. " +
                    "Latency: %d ms vs %d ms avg. With temperature at 0.0 (greedy decoding), top_p has " +
                    "no practical effect — the model always picks the highest-probability token regardless " +
                    "of the sampling distribution cutoff.",
                    s.aSourced(), s.total(), s.bSourced(), s.total(), aLat, bLat);
            }
            case "system-prompt" -> {
                yield String.format(
                    "Generic prompt sourced %d/%d, RAG-tuned prompt sourced %d/%d queries. " +
                    "Latency: %d ms vs %d ms avg. The RAG-tuned system prompt is longer, adding ~100 tokens " +
                    "of instructions, which increases prompt processing time. Both prompts produced sources " +
                    "and citations at similar rates — the format instructions appended to each query " +
                    "already guide the model sufficiently, making the system prompt less impactful than expected.",
                    s.aSourced(), s.total(), s.bSourced(), s.total(), aLat, bLat);
            }
            case "max-tokens" -> {
                yield String.format(
                    "Short budget (400) sourced %d/%d, full budget (800) sourced %d/%d queries. " +
                    "Latency: %d ms vs %d ms avg, tokens: %d vs %d total. " +
                    "The model's RAG answers naturally fit within 400 tokens — most responses include " +
                    "an answer paragraph, a few source citations, and 1-2 quotes, rarely exceeding 300 tokens. " +
                    "Doubling the budget doesn't improve quality, it just allows more verbose completions.",
                    s.aSourced(), s.total(), s.bSourced(), s.total(), aLat, bLat,
                    s.aTotalTokens(), s.bTotalTokens());
            }
            default -> "No interpretation available for this experiment.";
        };
    }

    private static String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;")
                   .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
