package com.moveinsync.config.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;

public final class ExplainResult {
    private final String key;
    private final Object resolvedValue;
    private final ScopeLevel winningLevel;
    private final ScopeKey winningScope;
    private final List<EvaluationStep> trace;

    @JsonCreator
    public ExplainResult(
            @JsonProperty("key") String key,
            @JsonProperty("resolvedValue") Object resolvedValue,
            @JsonProperty("winningLevel") ScopeLevel winningLevel,
            @JsonProperty("winningScope") ScopeKey winningScope,
            @JsonProperty("trace") List<EvaluationStep> trace) {
        this.key = key;
        this.resolvedValue = resolvedValue;
        this.winningLevel = winningLevel;
        this.winningScope = winningScope;
        this.trace = trace != null ? Collections.unmodifiableList(trace) : Collections.emptyList();
    }

    public String getKey() {
        return key;
    }

    public Object getResolvedValue() {
        return resolvedValue;
    }

    public ScopeLevel getWinningLevel() {
        return winningLevel;
    }

    public ScopeKey getWinningScope() {
        return winningScope;
    }

    public List<EvaluationStep> getTrace() {
        return trace;
    }

    public static final class EvaluationStep {
        private final ScopeLevel level;
        private final ScopeKey scopeKey;
        private final boolean found;
        private final Object candidateValue;
        private final String note;

        @JsonCreator
        public EvaluationStep(
                @JsonProperty("level") ScopeLevel level,
                @JsonProperty("scopeKey") ScopeKey scopeKey,
                @JsonProperty("found") boolean found,
                @JsonProperty("candidateValue") Object candidateValue,
                @JsonProperty("note") String note) {
            this.level = level;
            this.scopeKey = scopeKey;
            this.found = found;
            this.candidateValue = candidateValue;
            this.note = note;
        }

        public ScopeLevel getLevel() {
            return level;
        }

        public ScopeKey getScopeKey() {
            return scopeKey;
        }

        public boolean isFound() {
            return found;
        }

        public Object getCandidateValue() {
            return candidateValue;
        }

        public String getNote() {
            return note;
        }
    }
}
