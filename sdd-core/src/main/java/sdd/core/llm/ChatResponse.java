package sdd.core.llm;

public record ChatResponse(ChatMessage message, String finishReason, Usage usage) {}
