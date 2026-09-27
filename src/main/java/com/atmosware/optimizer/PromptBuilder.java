package com.atmosware.optimizer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PromptBuilder {

    public enum Mode {
        REVIEW("review.md"),
        PERFORMANCE("performance.md"),
        REFACTOR("refactor.md"),
        SECURITY("security.md");

        private final String file;

        Mode(String file) {
            this.file = file;
        }
    }

    public String buildSystemPrompt(CodeContext context) throws IOException {
        return load("system.md").replace("{{context}}", context.summary());
    }

    public String buildUserPrompt(CodeContext context, String source, Mode mode) throws IOException {
        return load(mode.file)
                .replace("{{checklist}}", formatChecklist(context, mode))
                .replace("{{code}}", numbered(source));
    }

    private String load(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/prompts/" + name)) {
            if (in == null) throw new IOException("Prompt dosyası bulunamadı: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String numbered(String source) {
        StringBuilder sb = new StringBuilder();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    private String formatChecklist(CodeContext context, Mode mode) {
        StringBuilder sb = new StringBuilder();
        for (String item : checklist(context, mode)) {
            sb.append("- ").append(item).append("\n");
        }
        return sb.toString();
    }

    private List<String> checklist(CodeContext context, Mode mode) {
        List<String> items = new ArrayList<>();

        if (mode == Mode.REVIEW || mode == Mode.PERFORMANCE) {
            items.add("Collection choice: List.contains instead of Set, wrong initial capacity");
            items.add("Unnecessary intermediate collections and autoboxing");
            items.add("String concatenation inside loops");
            if (context.jpa()) {
                items.add("JPA: N+1 query risk, FetchType.EAGER usage, @Transactional scope");
            }
            if (context.concurrency()) {
                items.add("Concurrency: shared mutable state, synchronization scope");
            }
        }

        if (mode == Mode.REVIEW || mode == Mode.REFACTOR) {
            items.add("Resource management: try-with-resources, unclosed streams");
            items.add("Exception handling: swallowed catch blocks, exceptions used for flow control");
            if (context.spring()) {
                items.add("Spring: field injection instead of constructor injection");
            }
            if (context.longestMethodLines() > 30) {
                items.add("This class has a " + context.longestMethodLines()
                        + "-line method; check whether it does more than one thing");
            }
            if (context.methodCount() > 15) {
                items.add("This class has " + context.methodCount()
                        + " methods; check for single-responsibility violations");
            }
        }

        if (mode == Mode.SECURITY) {
            items.add("Input validation on request parameters and path variables");
            items.add("String concatenation used to build queries or file paths");
            items.add("Hardcoded credentials, tokens or connection strings");
            items.add("Sensitive data written to logs or exception messages");
            if (context.layer() == CodeContext.Layer.CONTROLLER) {
                items.add("Missing authorization checks on endpoints");
            }
        }

        switch (context.layer()) {
            case CONTROLLER -> {
                items.add("Controller: returning entities directly instead of DTOs");
                items.add("Controller: missing pagination on list endpoints");
                items.add("Controller: business logic that belongs in a service");
            }
            case SERVICE -> items.add("Service: transaction boundaries, orchestration logic mixed with data access");
            case REPOSITORY -> items.add("Repository: unbounded queries, missing projections, findAll on large tables");
            case ENTITY -> {
                items.add("Entity: equals/hashCode consistency, mutable identifiers");
                items.add("Entity: bidirectional relationships causing infinite loops");
            }
            case TEST -> items.add("Test: missing assertions, interdependent tests, over-mocking");
            default -> { }
        }

        return items;
    }
}