package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;
import dev.nexus.core.ai.CommentModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

/** Explicit opt-in only: sends a synthetic public comment, never user messages or credentials. */
@EnabledIfEnvironmentVariable(named="NEXUS_LIVE_AI_TEST", matches="true")
class LiveCommentModelTest {
    @Test void configuredProviderReturnsStructuredPublicCommentAnalysis() {
        var client = new CommentModel(new ObjectMapper(), true, setting("NEXUS_AI_BASE_URL"), setting("NEXUS_AI_API_KEY"),
            setting("NEXUS_AI_MODEL"), setting("NEXUS_AI_PROXY"));
        var result = client.analyze("这篇桌面宠物开发教程很有帮助，请问支持断网时继续显示已收到的消息吗？（公开评论联调样例）");
        assertThat(result.summary()).isNotBlank();
        assertThat(result.category()).isEqualTo("question");
        assertThat(result.hasQuestion()).isTrue();
        assertThat(result.replyDraft()).isNotBlank();
    }
    private String setting(String key) { return System.getenv().getOrDefault(key, ""); }
}
