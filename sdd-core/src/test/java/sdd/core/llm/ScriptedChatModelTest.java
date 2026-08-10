package sdd.core.llm;

import org.junit.jupiter.api.Test;
import sdd.core.testing.ScriptedChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ScriptedChatModelTest {
    @Test
    void servesResponsesInOrderAndRecordsRequests() {
        ChatResponse first = new ChatResponse(ChatMessage.assistant("one"), "stop", new Usage(10, 2));
        ChatResponse second = new ChatResponse(ChatMessage.assistant("two"), "stop", new Usage(12, 3));
        ScriptedChatModel model = new ScriptedChatModel(List.of(first, second));

        ChatRequest req = new ChatRequest("m", List.of(ChatMessage.user("hi")), List.of(), 100, 0.0);
        assertThat(model.complete(req)).isSameAs(first);
        assertThat(model.complete(req)).isSameAs(second);
        assertThat(model.requests()).hasSize(2);
        assertThatThrownBy(() -> model.complete(req)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void messageFactoriesSetRoles() {
        assertThat(ChatMessage.system("s").role()).isEqualTo("system");
        assertThat(ChatMessage.user("u").role()).isEqualTo("user");
        assertThat(ChatMessage.assistant("a").role()).isEqualTo("assistant");
        ChatMessage tool = ChatMessage.tool("call-1", "result");
        assertThat(tool.role()).isEqualTo("tool");
        assertThat(tool.toolCallId()).isEqualTo("call-1");
    }
}
