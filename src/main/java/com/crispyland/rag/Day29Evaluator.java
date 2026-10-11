package com.crispyland.rag;

import com.crispyland.agent.AgentProperties;
import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.llm.LlmProviderSwitch;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.usage.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Day 29 evaluator: parameter sweep comparing one changed parameter at a time.
 * Runs a reduced set of queries (4) for each experiment, showing how each
 * parameter affects quality, speed, and resource usage.
 */
@Service
@ConditionalOnProperty(name = "rag.evaluation.enabled", havingValue = "true")
public class Day29Evaluator {

    private static final Logger log = LoggerFactory.getLogger(Day29Evaluator.class);

    private final RagSearcher searcher;
    private final LlmClient llm;
    private final RagProperties ragProps;
    private final AgentProperties agentProps;

    public Day29Evaluator(RagSearcher searcher, LlmClient llm,
                          RagProperties ragProps, AgentProperties agentProps) {
        this.searcher = searcher;
        this.llm = llm;
        this.ragProps = ragProps;
        this.agentProps = agentProps;
    }

    // ── Records ──────────────────────────────────────────────────────────────

    public record Query(int number, String text, String topic) {}

    /** Defines one A/B experiment: what parameter changes between the two runs. */
    public record Experiment(String id, String name, String description,
                             String paramName, String valueA, String valueB) {}

    /** Settings for one side of an experiment. */
    public record RunConfig(String model, Double temperature, Double topP,
                            Integer maxTokens, String systemPrompt) {}

    /** One side's answer to one query. */
    public record RunResult(
            String label, String badge,
            String rawAnswer, RagAnswer parsedAnswer,
            boolean hasSources, boolean hasQuotes,
            int quotesVerified, int quotesRejected, int quotesTotal,
            TokenUsage usage, long latencyMs,
            String model, String finishReason) {}

    /** Side-by-side for one query within one experiment. */
    public record QueryComparison(
            int number, String question, String topic,
            List<String> retrievedSources, long ragLatencyMs,
            RunResult sideA, RunResult sideB) {}

    /** One complete experiment result. */
    public record ExperimentResult(Experiment experiment,
                                   List<QueryComparison> comparisons,
                                   ExperimentSummary summary) {}

    public record ExperimentSummary(
            int total,
            int aSourced, int bSourced,
            int aQuoted, int bQuoted,
            long aAvgLatencyMs, long bAvgLatencyMs,
            long aTotalTokens, long bTotalTokens,
            int aErrors, int bErrors) {}

    /** Full evaluation: all experiments. */
    public record EvalResults(List<ExperimentResult> experiments) {}

    // ── Queries (reduced set) ────────────────────────────────────────────────

    private static final List<Query> QUERIES = List.of(
        new Query(1, "What memory layers exist in this agent and how do they differ?",
                "Architecture"),
        new Query(2, "How does the MemoryExtractor decide which facts to keep and which to replace?",
                "Memory"),
        new Query(3, "How are MCP tools discovered and offered to the model?",
                "MCP integration"),
        new Query(4, "Describe the RAG pipeline from query to answer. What steps does it take?",
                "RAG pipeline")
    );

    private static final RagConfig EVAL_CONFIG = new RagConfig(10, 3, 0.30f, true, 5, false);

    private static final String DEFAULT_SYSTEM =
            "You are a concise, helpful assistant that answers questions about a codebase. " +
            "Answer directly and cite your sources. Include verbatim quotes from the provided context.";

    private static final String RAG_TUNED_SYSTEM =
            "You answer questions about a codebase using ONLY the provided context. " +
            "Rules: 1) Use ONLY information from the context. Never invent facts. " +
            "2) Cite sources as [1], [2] matching context numbers. " +
            "3) Include at least one verbatim quote using > \"quote\" — FileName.java format. " +
            "4) If context is insufficient, say so. 5) Be concise.";

    private static final String FORMAT_INSTRUCTION = """

            IMPORTANT: Structure your response with these EXACT headings:

            ### Answer
            Your concise answer. Use citation markers like [1], [2] to reference sources.

            ### Sources
            List each source you used, one per line:
            - FileName.java | section name (chunk-id)

            ### Quotes
            For each key claim, include a verbatim quote from the context:
            > "exact words from the context" — FileName.java

            Rules:
            - Only cite sources from the provided context. Do not invent sources.
            - Only quote text that appears word-for-word in the context.
            - If the context does not contain enough information, say so and write (none).
            """;

    // ── Experiments ──────────────────────────────────────────────────────────

    private List<Experiment> buildExperiments() {
        String baseModel = agentProps.ollama().model();
        String q8Model = "llama3.2:3b-text-q8_0";
        String smallModel = "llama3.2:1b";

        List<Experiment> exps = new ArrayList<>();

        exps.add(new Experiment("temperature", "Temperature",
                "Greedy decoding vs creative sampling",
                "temperature", "0.0", "0.7"));

        exps.add(new Experiment("quant", "Quantization",
                "Q4_K_M (4-bit, 2GB) vs Q8_0 (8-bit, 3.4GB)",
                "model", baseModel, q8Model));

        exps.add(new Experiment("model-size", "Model Size",
                "3B parameters vs 1B parameters",
                "model", baseModel, smallModel));

        exps.add(new Experiment("context-window", "Context Window",
                "Default (131K) vs limited (4K tokens)",
                "model", baseModel, baseModel + "-ctx4k"));

        exps.add(new Experiment("repeat-penalty", "Repeat Penalty",
                "No penalty (1.0) vs penalty (1.2)",
                "model", baseModel, baseModel + "-rp12"));

        exps.add(new Experiment("top-p", "Top-P Sampling",
                "Full distribution (1.0) vs nucleus (0.9)",
                "top_p", "1.0", "0.9"));

        exps.add(new Experiment("system-prompt", "System Prompt",
                "Generic assistant vs RAG-tuned instructions",
                "system_prompt", "Generic", "RAG-tuned"));

        exps.add(new Experiment("max-tokens", "Max Tokens",
                "Short budget (400) vs full budget (800)",
                "max_tokens", "400", "800"));

        return exps;
    }

    // ── Main entry point ─────────────────────────────────────────────────────

    public EvalResults evaluateAll(long delayMs) {
        if (!(llm instanceof LlmProviderSwitch switcher)) {
            throw new IllegalStateException(
                    "Day 29 comparison requires LlmProviderSwitch (Ollama must be enabled).");
        }

        LlmProviderSwitch.Provider original = switcher.activeProvider();
        switcher.switchTo(LlmProviderSwitch.Provider.OLLAMA);

        String baseModel = agentProps.ollama().model();
        List<Experiment> experiments = buildExperiments();
        List<ExperimentResult> results = new ArrayList<>();

        try {
            for (Experiment exp : experiments) {
                log.info("Day29 experiment: {} ({})", exp.name(), exp.description());
                ExperimentResult result = runExperiment(exp, switcher, baseModel, delayMs);
                results.add(result);
                log.info("  A: avg {}ms, sourced={}/{} | B: avg {}ms, sourced={}/{}",
                        result.summary().aAvgLatencyMs(), result.summary().aSourced(), result.summary().total(),
                        result.summary().bAvgLatencyMs(), result.summary().bSourced(), result.summary().total());
                if (delayMs > 0) Thread.sleep(delayMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            switcher.setOllamaModelOverride(null);
            switcher.switchTo(original);
        }

        return new EvalResults(results);
    }

    // ── Experiment runner ────────────────────────────────────────────────────

    private ExperimentResult runExperiment(Experiment exp, LlmProviderSwitch switcher,
                                           String baseModel, long delayMs) {
        RunConfig configA = buildConfig(exp, true, baseModel);
        RunConfig configB = buildConfig(exp, false, baseModel);

        List<QueryComparison> comparisons = new ArrayList<>();
        for (Query query : QUERIES) {
            log.info("  Q{}: {}", query.number(), query.text());

            // Side A
            switcher.setOllamaModelOverride(configA.model());
            RagSearchResult ragA = searcher.search(query.text(), List.of(), EVAL_CONFIG);
            RunResult a = runQuery(query, ragA, configA, "A", "side-a");

            try { if (delayMs > 0) Thread.sleep(delayMs); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Side B
            switcher.setOllamaModelOverride(configB.model());
            RagSearchResult ragB = searcher.search(query.text(), List.of(), EVAL_CONFIG);
            RunResult b = runQuery(query, ragB, configB, "B", "side-b");

            switcher.setOllamaModelOverride(null);

            List<String> sources = ragA.finalChunks().stream()
                    .map(sc -> sc.chunk().sourceFile() + " | " + sc.chunk().section()).toList();
            long ragLatency = ragA.debug() != null ? ragA.debug().totalLatencyMs() : 0;

            comparisons.add(new QueryComparison(query.number(), query.text(), query.topic(),
                    sources, ragLatency, a, b));

            log.info("    A: {}ms sources={} | B: {}ms sources={}",
                    a.latencyMs(), a.hasSources(), b.latencyMs(), b.hasSources());
        }

        return new ExperimentResult(exp, comparisons, summarize(comparisons));
    }

    private RunConfig buildConfig(Experiment exp, boolean sideA, String baseModel) {
        String model = baseModel;
        double temperature = 0.0;
        Double topP = null;
        int maxTokens = 800;
        String systemPrompt = DEFAULT_SYSTEM;

        switch (exp.id()) {
            case "temperature" -> temperature = sideA ? 0.0 : 0.7;
            case "quant" -> model = sideA ? baseModel : "llama3.2:3b-text-q8_0";
            case "model-size" -> model = sideA ? baseModel : "llama3.2:1b";
            case "context-window" -> model = sideA ? baseModel : baseModel + "-ctx4k";
            case "repeat-penalty" -> model = sideA ? baseModel : baseModel + "-rp12";
            case "top-p" -> topP = sideA ? 1.0 : 0.9;
            case "system-prompt" -> systemPrompt = sideA ? DEFAULT_SYSTEM : RAG_TUNED_SYSTEM;
            case "max-tokens" -> maxTokens = sideA ? 400 : 800;
        }

        return new RunConfig(model, temperature, topP, maxTokens, systemPrompt);
    }

    // ── Single query runner ──────────────────────────────────────────────────

    private RunResult runQuery(Query query, RagSearchResult ragResult,
                               RunConfig config, String label, String badge) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(config.systemPrompt()));

        StringBuilder prompt = new StringBuilder();
        if (!ragResult.isEmpty()) {
            prompt.append("Relevant context from the codebase:\n\n");
            for (int i = 0; i < ragResult.finalChunks().size(); i++) {
                ScoredChunk sc = ragResult.finalChunks().get(i);
                Chunk c = sc.chunk();
                prompt.append("--- [").append(i + 1).append("] ")
                      .append(c.sourceFile()).append(" | ").append(c.section())
                      .append(" (").append(c.chunkId()).append(") ---\n");
                prompt.append(c.text()).append("\n\n");
            }
            prompt.append("---\n\n");
        }
        prompt.append("Question: ").append(query.text());
        prompt.append(FORMAT_INSTRUCTION);
        messages.add(Message.user(prompt.toString()));

        long start = System.nanoTime();
        try {
            ChatRequest req = new ChatRequest(
                    config.model(), messages, config.temperature(),
                    config.maxTokens(), "", List.of(), "",
                    List.of(), List.of(), config.topP());
            ChatResponse resp = llm.complete(req);
            long latency = (System.nanoTime() - start) / 1_000_000;

            String raw = resp.content().strip();
            RagAnswer parsed = RagAnswer.parse(raw, ragResult.finalChunks());

            return new RunResult(label, badge, raw, parsed,
                    parsed.hasSources(), parsed.hasQuotes(),
                    (int) parsed.quotes().stream()
                            .filter(q -> q.status() == RagAnswer.QuoteStatus.VERIFIED).count(),
                    (int) parsed.quotes().stream()
                            .filter(q -> q.status() == RagAnswer.QuoteStatus.REJECTED).count(),
                    parsed.quotes().size(),
                    resp.usage(), latency, resp.model(), resp.finishReason());
        } catch (Exception e) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            log.error("    {} failed: {}", label, e.getMessage());
            RagAnswer empty = new RagAnswer(e.getMessage(), "ERROR: " + e.getMessage(),
                    List.of(), List.of(), false);
            return new RunResult(label, badge, e.getMessage(), empty,
                    false, false, 0, 0, 0,
                    TokenUsage.NONE, latency, config.model(), "error");
        }
    }

    // ── Summarize ────────────────────────────────────────────────────────────

    private ExperimentSummary summarize(List<QueryComparison> comparisons) {
        int total = comparisons.size();
        int aS = 0, bS = 0, aQ = 0, bQ = 0;
        long aLat = 0, bLat = 0, aTok = 0, bTok = 0;
        int aErr = 0, bErr = 0;

        for (QueryComparison c : comparisons) {
            if (c.sideA().hasSources()) aS++;
            if (c.sideB().hasSources()) bS++;
            if (c.sideA().hasQuotes()) aQ++;
            if (c.sideB().hasQuotes()) bQ++;
            aLat += c.sideA().latencyMs();
            bLat += c.sideB().latencyMs();
            aTok += c.sideA().usage().totalTokens();
            bTok += c.sideB().usage().totalTokens();
            if ("error".equals(c.sideA().finishReason())) aErr++;
            if ("error".equals(c.sideB().finishReason())) bErr++;
        }

        return new ExperimentSummary(total, aS, bS, aQ, bQ,
                total > 0 ? aLat / total : 0, total > 0 ? bLat / total : 0,
                aTok, bTok, aErr, bErr);
    }
}
