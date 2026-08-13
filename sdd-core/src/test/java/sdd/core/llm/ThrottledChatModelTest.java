package sdd.core.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThrottledChatModelTest {
    private static final ChatRequest REQ = new ChatRequest("m", List.of(), List.of(), 16, 0.0);

    @Test
    void acquiresAPermitAroundTheCallAndReleasesIt() {
        Semaphore permits = new Semaphore(1);
        ChatModel inner = req -> {
            assertThat(permits.availablePermits()).isZero();   // held during the call
            return new ChatResponse(new ChatMessage("assistant", "ok", List.of(), null),
                    "stop", new Usage(1, 1));
        };

        ChatResponse response = new ThrottledChatModel(inner, permits).complete(REQ);

        assertThat(response.message().content()).isEqualTo("ok");
        assertThat(permits.availablePermits()).isEqualTo(1);   // released after
    }

    @Test
    void releasesThePermitWhenTheDelegateThrows() {
        Semaphore permits = new Semaphore(1);
        ChatModel inner = req -> {
            throw new ModelException("boom", 500);
        };

        assertThatThrownBy(() -> new ThrottledChatModel(inner, permits).complete(REQ))
                .isInstanceOf(ModelException.class);
        assertThat(permits.availablePermits()).isEqualTo(1);
    }
}
