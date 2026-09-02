package com.databuff.apm.web.ai.platform.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpertRuntimeEventAccumulatorTest {

    @Test
    void keepsTextWhenStreamCompletesWithoutTool() {
        ExpertRuntimeEventAccumulator outcome = new ExpertRuntimeEventAccumulator();

        outcome.accept(ExpertRuntimeEvent.text("完整"));
        outcome.accept(ExpertRuntimeEvent.text("终稿"));

        assertThat(outcome.replyCandidate()).isEqualTo("完整终稿");
        assertThat(outcome.toolActivitySeen()).isFalse();
    }

    @Test
    void keepsOnlyTextAfterLastToolAcrossMultipleToolSteps() {
        ExpertRuntimeEventAccumulator outcome = new ExpertRuntimeEventAccumulator();

        outcome.accept(ExpertRuntimeEvent.text("调用工具一之前"));
        outcome.accept(ExpertRuntimeEvent.toolCall("tool-1"));
        outcome.accept(ExpertRuntimeEvent.toolResult("result-1"));
        outcome.accept(ExpertRuntimeEvent.text("调用工具二之前"));
        outcome.accept(ExpertRuntimeEvent.toolCall("tool-2"));
        outcome.accept(ExpertRuntimeEvent.toolResult("result-2"));
        outcome.accept(ExpertRuntimeEvent.text("最终"));
        outcome.accept(ExpertRuntimeEvent.text("答复"));

        assertThat(outcome.replyCandidate()).isEqualTo("最终答复");
        assertThat(outcome.toolActivitySeen()).isTrue();
    }

    @Test
    void rejectsRuntimeErrorInsteadOfStartingAnImplicitSecondInvocation() {
        ExpertRuntimeEventAccumulator outcome = new ExpertRuntimeEventAccumulator();

        assertThatThrownBy(() -> outcome.accept(ExpertRuntimeEvent.error("model unavailable")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model unavailable");
    }
}
