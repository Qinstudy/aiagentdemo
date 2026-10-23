package com.zoujuexian.aiagentdemo.service.rag;

import com.zoujuexian.aiagentdemo.service.rag.chunk.ChunkSplitter;
import com.zoujuexian.aiagentdemo.service.rag.chunk.definite.TextSplitter;
import com.zoujuexian.aiagentdemo.service.rag.retrieve.impl.Bm25Retriever;
import com.zoujuexian.aiagentdemo.service.rag.retrieve.MultiRetriever;
import com.zoujuexian.aiagentdemo.service.rag.retrieve.impl.QueryRewriteRetriever;
import com.zoujuexian.aiagentdemo.service.rag.retrieve.Retriever;
import com.zoujuexian.aiagentdemo.service.rag.retrieve.impl.SemanticRetriever;
import com.zoujuexian.aiagentdemo.service.rag.rerank.LlmReranker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * RAG 服务封装
 * <p>
 * 封装完整的 RAG 流程（分块 → 向量化 → 多路召回 → LLM 重排），
 * 对外暴露 query(question) 方法，供 Agent 作为 Function Tool 调用。
 */
@Component
public class RagService {

    private static final String KNOWLEDGE_DIR = "knowledge";

    /** 文本分块大小（字符数） */
    private static final int CHUNK_SIZE = 500;

    /** 相邻块重叠字符数 */
    private static final int CHUNK_OVERLAP = 50;

    /** 多路召回候选数量 */
    private static final int RECALL_CANDIDATE_COUNT = 9;

    /** 最终送入 Prompt 的文档块数量 */
    private static final int TOP_K = 3;

    private final VectorStore vectorStore;
    private final ChunkSplitter chunkSplitter;
    private final MultiRetriever multiRecaller;
    private final LlmReranker llmReranker;

    private boolean knowledgeLoaded = false;

    public RagService(
            EmbeddingModel embeddingModel,
            ChatClient chatClient,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${rag.rerank.path:/rerank}") String rerankPath,
            @Value("${rag.rerank.model:rerank}") String rerankModel) throws IOException, URISyntaxException {
        this.vectorStore = new VectorStore(embeddingModel);
        this.chunkSplitter = new TextSplitter(CHUNK_SIZE, CHUNK_OVERLAP); // 构造函数初始化，创建TextSplitter 对象

        List<Retriever> retrievers = List.of(
                new SemanticRetriever(vectorStore),
                new Bm25Retriever(vectorStore),
                new QueryRewriteRetriever(vectorStore, chatClient)
        );
        this.multiRecaller = new MultiRetriever(retrievers);
        this.llmReranker = new LlmReranker(baseUrl, apiKey, rerankPath, rerankModel);
        loadKnowledgeBase(KNOWLEDGE_DIR);
    }

    /**
     * 加载知识库目录下的所有 .txt 文件
     *
     * @param knowledgeDir 知识库目录路径（相对于 classpath resources）
     */
    public void loadKnowledgeBase(String knowledgeDir) throws IOException, URISyntaxException {
        URL resourceUrl = getClass().getClassLoader().getResource(knowledgeDir);
        if (resourceUrl == null) {
            throw new IOException("知识库目录不存在: " + knowledgeDir);
        }

        Path dirPath = Paths.get(resourceUrl.toURI());
        List<Path> textFiles = Files.list(dirPath)
                .filter(path -> path.toString().endsWith(".txt"))
                .toList();

        if (textFiles.isEmpty()) {
            throw new IOException("知识库目录中没有找到 .txt 文件: " + knowledgeDir);
        }

        System.out.println("=== 加载知识库 ===");
        System.out.println("发现 " + textFiles.size() + " 个知识库文件\n");

        for (Path filePath : textFiles) {
            String fileName = filePath.getFileName().toString();
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            System.out.println("正在处理文件: " + fileName + "（" + content.length() + " 字符）");

            List<Document> chunks = chunkSplitter.split(content, fileName);
            System.out.println(chunkSplitter.name() + "完成，共 " + chunks.size() + " 个文本块");

            vectorStore.addDocuments(chunks);
        }

        knowledgeLoaded = true;
        System.out.println("知识库加载完成。\n");
    }

    /**
     * 检索知识库中与问题最相关的文档内容
     * <p>
     * 流程：多路召回 → LLM 重排 → 拼接上下文
     *
     * @param question 用户问题
     * @return 检索到的相关知识文本（多个文档块拼接）
     */
    public String query(String question) {
        return query(question, true);
    }

    /**
     * 检索知识库中与问题最相关的文档内容（支持开启/关闭 Rerank 对照）
     *
     * @param question     用户问题
     * @param enableRerank 是否启用 Rerank 深度精排模型
     * @return 检索到的相关知识文本（多个文档块拼接）
     */
    public String query(String question, boolean enableRerank) {
        if (!knowledgeLoaded) {
            return "知识库尚未加载，请先调用 loadKnowledgeBase 加载知识库。";
        }

        // 1. 多路召回
        List<Document> candidates = multiRecaller.retrieve(question, RECALL_CANDIDATE_COUNT);
        if (candidates.isEmpty()) {
            return "知识库中没有找到与问题相关的内容。";
        }

        // 2. LLM 重排（对照实验开关）
        List<Document> relevantDocuments;
        if (enableRerank) {
            relevantDocuments = llmReranker.rerank(question, candidates, TOP_K);
        } else {
            System.out.println("  [未开启 Rerank 对照组] 直接取多路召回 RRF 融合的前 " + TOP_K + " 个候选");
            relevantDocuments = candidates.stream().limit(TOP_K).toList();
        }

        // 3. 拼接上下文
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < relevantDocuments.size(); i++) {
            contextBuilder.append("【参考资料 ").append(i + 1).append("】\n");
            contextBuilder.append(relevantDocuments.get(i).getContent());
            contextBuilder.append("\n\n");
        }

        return contextBuilder.toString().trim();
    }

    /**
     * 知识库是否已加载
     */
    public boolean isKnowledgeLoaded() {
        return knowledgeLoaded;
    }


}
