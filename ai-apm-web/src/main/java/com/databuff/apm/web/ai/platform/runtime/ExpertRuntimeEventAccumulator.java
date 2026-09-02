package com.databuff.apm.web.ai.platform.runtime;

/**
 * Reduces one runtime event stream to the assistant reply candidate produced after the last tool
 * call. Text followed by a tool call is process narration; text left when the stream completes is
 * the only reply candidate. One accumulator represents one runtime invocation.
 */
public final class ExpertRuntimeEventAccumulator {

    private final StringBuilder replyCandidate = new StringBuilder();
    private boolean toolActivitySeen;

    public void accept(ExpertRuntimeEvent event) {
        if (event == null || event.type() == null) {
            return;
        }
        switch (event.type()) {
            case "error" -> throw new IllegalStateException(errorMessage(event));
            case "tool_call" -> {
                toolActivitySeen = true;
                // Everything emitted before this tool belongs to the execution trace, not reply.
                replyCandidate.setLength(0);
            }
            case "text" -> {
                if (event.content() != null) {
                    replyCandidate.append(event.content());
                }
            }
            default -> {
                // reasoning / tool_result are trace events and do not contribute reply text.
            }
        }
    }

    public String replyCandidate() {
        return replyCandidate.toString().trim();
    }

    public boolean toolActivitySeen() {
        return toolActivitySeen;
    }

    private static String errorMessage(ExpertRuntimeEvent event) {
        return event.content() == null || event.content().isBlank()
                ? "AgentScope stream failed"
                : event.content().trim();
    }
}
