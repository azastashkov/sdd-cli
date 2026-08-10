package sdd.core.testing;

import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ScriptedChatModel implements ChatModel {
    private final Deque<ChatResponse> script;
    private final List<ChatRequest> requests = new ArrayList<>();

    public ScriptedChatModel(List<ChatResponse> responses) {
        this.script = new ArrayDeque<>(responses);
    }

    @Override
    public ChatResponse complete(ChatRequest req) {
        requests.add(req);
        ChatResponse next = script.poll();
        if (next == null) {
            throw new IllegalStateException("ScriptedChatModel exhausted after " + requests.size() + " calls");
        }
        return next;
    }

    public List<ChatRequest> requests() { return List.copyOf(requests); }
}
