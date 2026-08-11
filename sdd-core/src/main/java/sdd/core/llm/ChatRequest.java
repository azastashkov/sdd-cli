package sdd.core.llm;

import java.util.List;

public record ChatRequest(String model, List<ChatMessage> messages, List<ToolSpec> tools,
                          Integer maxTokens, Double temperature) {}
