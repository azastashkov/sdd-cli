package sdd.core.llm;

public interface ChatModel {
    ChatResponse complete(ChatRequest req) throws ModelException;
}
