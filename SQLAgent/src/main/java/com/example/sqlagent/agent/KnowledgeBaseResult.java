package com.example.sqlagent.agent;

import java.util.List;

public record KnowledgeBaseResult(
        boolean found,
        String context,
        List<Source> sources
) {

    public KnowledgeBaseResult {
        context = context == null ? "" : context;
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static KnowledgeBaseResult empty() {
        return new KnowledgeBaseResult(false, "", List.of());
    }

    public record Source(
            String documentName,
            String text,
            double score
    ) {
    }
}
