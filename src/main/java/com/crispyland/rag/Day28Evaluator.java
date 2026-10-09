package com.crispyland.rag;

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
 * Day 28 evaluator: runs the same RAG queries through both Cerebras (cloud) and Ollama (local),
 * captures response text, latency, and token counts, then produces a side-by-side comparison.
 */
@Service
@ConditionalOnProperty(name = "rag.evaluation.enabled", havingValue = "true")
public class Day28Evaluator {

    private static final Logger log = LoggerFactory.getLogger(Day28Evaluator.class);

    private final RagSearcher searcher;
    private final LlmClient llm;
    private final RagProperties props;

    public Day28Evaluator(RagSearcher searcher, LlmClient llm, RagProperties props) {
        this.searcher = searcher;
        this.llm = llm;
        this.props = props;
    }

    /** One question to ask both providers. */
    public record Query(int number, String text, String topic) {}

    /** One provider's answer to one query. */
    public record ProviderResult(
            String provider, String badge,
            String rawAnswer, RagAnswer parsedAnswer,
            boolean hasSources, boolean hasQuotes,
            int quotesVerified, int quotesRejected, int quotesTotal,
            TokenUsage answerUsage, long answerLatencyMs,
            String model, String finishReason) {}

    /** Side-by-side comparison for one query. */
    public record Comparison(
            int number, String question, String topic,
            List<String> retrievedSources, long ragLatencyMs,
            ProviderResult cloud, ProviderResult local) {}

    /** Full evaluation result. */
    public record EvalResults(List<Comparison> comparisons, Summary summary) {}

    /** Aggregate metrics across all queries. */
    public record Summary(
            int totalQueries,
            int cloudSourced, int localSourced,
            int cloudQuoted, int localQuoted,
            long cloudAvgLatencyMs, long localAvgLatencyMs,
            long cloudTotalTokens, long localTotalTokens,
            int cloudErrors, int localErrors) {}

    private static final List<Query> QUERIES = List.of(
        new Query(1, "What memory layers exist in this agent and how do they differ?",
                "Architecture"),
        new Query(2, "How does the MemoryExtractor decide which facts to keep and which to replace?",
                "Memory"),
        new Query(3, "Explain how the task state machine works. What stages exist and what transitions are allowed?",
                "Task system"),
        new Query(4, "How does the context planner calculate the token budget for a turn?",
                "Token budget"),
        new Query(5, "What is the difference between the invariant guard and the input/output policies?",
                "Safety"),
        new Query(6, "How are MCP tools discovered and offered to the model?",
                "MCP integration"),
        new Query(7, "Describe the RAG pipeline from query to answer. What steps does it take?",
                "RAG pipeline"),
        new Query(8, "How does conversation branching work? What happens to memory when you fork?",
                "Branching")
    );

    private static final RagConfig EVAL_CONFIG = new RagConfig(10, 3, 0.30f, true, 5, false);

    private static final String SYSTEM_PROMPT =
            "You are a concise, helpful assistant that answers questions about a codebase. " +
            "Answer directly and cite your sources. Include verbatim quotes from the provided context.";

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

    public EvalResults evaluateAll(long delayMs) {
        if (!(llm instanceof LlmProviderSwitch switcher)) {
            throw new IllegalStateException(
                    "Day 28 comparison requires LlmProviderSwitch (Ollama must be enabled).");
        }

        // Remember which provider was active so we can restore it.
        LlmProviderSwitch.Provider original = switcher.activeProvider();

        List<Comparison> comparisons = new ArrayList<>();
        try {
            for (Query query : QUERIES) {
                log.info("Day28 query {}: {}", query.number(), query.text());
                Comparison c = runComparison(query, switcher, delayMs);
                comparisons.add(c);
                log.info("  Cloud: {}ms, sources={} | Local: {}ms, sources={}",
                        c.cloud().answerLatencyMs(), c.cloud().hasSources(),
                        c.local().answerLatencyMs(), c.local().hasSources());
                if (delayMs > 0) Thread.sleep(delayMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Always restore the original provider.
            switcher.switchTo(original);
        }
        return new EvalResults(comparisons, summarize(comparisons));
    }

    private Comparison runComparison(Query query, LlmProviderSwitch switcher, long delayMs) {
        // Each provider does its own full RAG pipeline (including reranking through its own LLM),
        // so the local run is fully local — no cloud calls at all.

        // Cloud: retrieval + reranking + generation all through Cerebras.
        switcher.switchTo(LlmProviderSwitch.Provider.CEREBRAS);
        RagSearchResult cloudRag = searcher.search(query.text(), List.of(), EVAL_CONFIG);
        ProviderResult cloud = runProvider(query, cloudRag, "Cerebras", "cloud",
                props.rerank().model());

        // Brief pause between providers.
        try { if (delayMs > 0) Thread.sleep(delayMs); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Local: retrieval + reranking + generation all through Ollama.
        switcher.switchTo(LlmProviderSwitch.Provider.OLLAMA);
        RagSearchResult localRag = searcher.search(query.text(), List.of(), EVAL_CONFIG);
        ProviderResult local = runProvider(query, localRag, "Ollama", "local", null);

        // Show the cloud retrieval sources in the comparison header (both should be similar
        // since the cosine search is the same — only the reranking order may differ).
        List<String> sources = cloudRag.finalChunks().stream()
                .map(sc -> sc.chunk().sourceFile() + " | " + sc.chunk().section()).toList();
        long ragLatency = cloudRag.debug() != null ? cloudRag.debug().totalLatencyMs() : 0;

        return new Comparison(query.number(), query.text(), query.topic(),
                sources, ragLatency, cloud, local);
    }

    private ProviderResult runProvider(Query query, RagSearchResult ragResult,
                                       String provider, String badge, String model) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(SYSTEM_PROMPT));

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
            // Model is overridden by LlmProviderSwitch for Ollama; for Cerebras use the configured model.
            String requestModel = (model != null) ? model : props.rerank().model();
            ChatRequest req = new ChatRequest(requestModel, messages, 0.0, 800, "", List.of(), "");
            ChatResponse resp = llm.complete(req);
            long latency = (System.nanoTime() - start) / 1_000_000;

            String raw = resp.content().strip();
            RagAnswer parsed = RagAnswer.parse(raw, ragResult.finalChunks());

            return new ProviderResult(provider, badge, raw, parsed,
                    parsed.hasSources(), parsed.hasQuotes(),
                    (int) parsed.quotes().stream()
                            .filter(q -> q.status() == RagAnswer.QuoteStatus.VERIFIED).count(),
                    (int) parsed.quotes().stream()
                            .filter(q -> q.status() == RagAnswer.QuoteStatus.REJECTED).count(),
                    parsed.quotes().size(),
                    resp.usage(), latency, resp.model(), resp.finishReason());
        } catch (Exception e) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            log.error("  {} failed: {}", provider, e.getMessage());
            RagAnswer empty = new RagAnswer(e.getMessage(), "ERROR: " + e.getMessage(),
                    List.of(), List.of(), false);
            return new ProviderResult(provider, badge, e.getMessage(), empty,
                    false, false, 0, 0, 0,
                    TokenUsage.NONE, latency, "", "error");
        }
    }

    private Summary summarize(List<Comparison> comparisons) {
        int total = comparisons.size();
        int cloudSourced = 0, localSourced = 0;
        int cloudQuoted = 0, localQuoted = 0;
        long cloudLatency = 0, localLatency = 0;
        long cloudTokens = 0, localTokens = 0;
        int cloudErrors = 0, localErrors = 0;

        for (Comparison c : comparisons) {
            if (c.cloud().hasSources()) cloudSourced++;
            if (c.local().hasSources()) localSourced++;
            if (c.cloud().hasQuotes()) cloudQuoted++;
            if (c.local().hasQuotes()) localQuoted++;
            cloudLatency += c.cloud().answerLatencyMs();
            localLatency += c.local().answerLatencyMs();
            cloudTokens += c.cloud().answerUsage().totalTokens();
            localTokens += c.local().answerUsage().totalTokens();
            if ("error".equals(c.cloud().finishReason())) cloudErrors++;
            if ("error".equals(c.local().finishReason())) localErrors++;
        }

        return new Summary(total,
                cloudSourced, localSourced, cloudQuoted, localQuoted,
                total > 0 ? cloudLatency / total : 0,
                total > 0 ? localLatency / total : 0,
                cloudTokens, localTokens, cloudErrors, localErrors);
    }
}
