package com.databuff.apm.ingest.trace;

import com.databuff.apm.common.model.DcSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Drop spans whose identity string matches configured exact values or regex patterns.
 * Match candidates (any hit drops the span):
 * <ul>
 *   <li>{@link DcSpan#resource} — OTel span name / SkyWalking operationName / SQL / Redis key, etc.</li>
 *   <li>{@link DcSpan#metaHttpUrl} — when set (HTTP interface identity in 接口分析)</li>
 * </ul>
 * Regexes are {@link Pattern#compile(String) compiled once} at construction; hot path only
 * {@link Matcher#reset(CharSequence) resets} a thread-local matcher (no per-span recompile).
 * Matched spans skip enrich / assemble / fill / metric / trace write.
 */
public final class SpanResourceIgnoreFilter {

    private static final Logger log = LoggerFactory.getLogger(SpanResourceIgnoreFilter.class);

    public static final SpanResourceIgnoreFilter NOOP = new SpanResourceIgnoreFilter(List.of(), List.of());

    private final Set<String> exactResources;
    /** Precompiled at construction; empty array when no regex configured. */
    private final CompiledPattern[] resourcePatterns;
    private final boolean empty;

    public SpanResourceIgnoreFilter(Collection<String> exactResources, Collection<String> resourceRegexes) {
        this.exactResources = compileExact(exactResources);
        this.resourcePatterns = compilePatterns(resourceRegexes);
        this.empty = this.exactResources.isEmpty() && this.resourcePatterns.length == 0;
    }

    public boolean isEmpty() {
        return empty;
    }

    /** @return true if the span should be dropped (ignored). */
    public boolean shouldIgnore(DcSpan span) {
        if (span == null || empty) {
            return false;
        }
        if (matches(span.resource)) {
            return true;
        }
        // HTTP 接口分析按 url 展示；resource 常为 span name，二者可能不一致
        return matches(span.metaHttpUrl);
    }

    private boolean matches(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        // Exact first: O(1) HashSet, avoid regex when possible.
        if (!exactResources.isEmpty() && exactResources.contains(value)) {
            return true;
        }
        for (CompiledPattern pattern : resourcePatterns) {
            if (pattern.matches(value)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> compileExact(Collection<String> exactResources) {
        if (exactResources == null || exactResources.isEmpty()) {
            return Set.of();
        }
        Set<String> exact = new LinkedHashSet<>();
        for (String value : exactResources) {
            if (value != null && !value.isBlank()) {
                exact.add(value.trim());
            }
        }
        return exact.isEmpty() ? Set.of() : Collections.unmodifiableSet(exact);
    }

    private static CompiledPattern[] compilePatterns(Collection<String> resourceRegexes) {
        if (resourceRegexes == null || resourceRegexes.isEmpty()) {
            return CompiledPattern.EMPTY;
        }
        List<CompiledPattern> patterns = new ArrayList<>();
        for (String regex : resourceRegexes) {
            if (regex == null || regex.isBlank()) {
                continue;
            }
            String trimmed = regex.trim();
            try {
                patterns.add(new CompiledPattern(Pattern.compile(trimmed)));
            } catch (PatternSyntaxException e) {
                log.warn("Invalid ingest.trace.ignore-resource-regex '{}': {}", trimmed, e.getMessage());
            }
        }
        return patterns.isEmpty() ? CompiledPattern.EMPTY : patterns.toArray(CompiledPattern.EMPTY);
    }

    /**
     * One precompiled regex with a thread-local {@link Matcher} so hot-path matching
     * does not allocate a new matcher per span.
     */
    static final class CompiledPattern {
        static final CompiledPattern[] EMPTY = new CompiledPattern[0];

        private final Pattern pattern;
        private final ThreadLocal<Matcher> matcher;

        CompiledPattern(Pattern pattern) {
            this.pattern = Objects.requireNonNull(pattern, "pattern");
            this.matcher = ThreadLocal.withInitial(() -> this.pattern.matcher(""));
        }

        boolean matches(String value) {
            Matcher m = matcher.get();
            m.reset(value);
            return m.matches();
        }

        String pattern() {
            return pattern.pattern();
        }
    }

    @Override
    public String toString() {
        return "SpanResourceIgnoreFilter{exact=" + exactResources.size()
                + ", regex=" + resourcePatterns.length + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SpanResourceIgnoreFilter that)) {
            return false;
        }
        if (!exactResources.equals(that.exactResources)) {
            return false;
        }
        if (resourcePatterns.length != that.resourcePatterns.length) {
            return false;
        }
        for (int i = 0; i < resourcePatterns.length; i++) {
            if (!resourcePatterns[i].pattern().equals(that.resourcePatterns[i].pattern())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = exactResources.hashCode();
        for (CompiledPattern pattern : resourcePatterns) {
            result = 31 * result + pattern.pattern().hashCode();
        }
        return result;
    }
}
