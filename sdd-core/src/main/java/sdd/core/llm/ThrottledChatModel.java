package sdd.core.llm;

import java.util.concurrent.Semaphore;

/** Caps concurrent model calls (design line 60: model_concurrency semaphore). A decorator on the
 *  existing ChatModel seam so neither the agent loop nor the orchestrator knows it is throttled. */
public final class ThrottledChatModel implements ChatModel {
    private final ChatModel delegate;
    private final Semaphore permits;

    public ThrottledChatModel(ChatModel delegate, Semaphore permits) {
        this.delegate = delegate;
        this.permits = permits;
    }

    @Override
    public ChatResponse complete(ChatRequest req) throws ModelException {
        permits.acquireUninterruptibly();
        try {
            return delegate.complete(req);
        } finally {
            permits.release();
        }
    }
}
