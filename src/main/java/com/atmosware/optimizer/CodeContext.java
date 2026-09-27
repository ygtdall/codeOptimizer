package com.atmosware.optimizer;

import java.util.Set;

public record CodeContext(
        String fileName,
        String className,
        String javaVersion,
        int lineCount,
        int methodCount,
        int longestMethodLines,
        Layer layer,
        boolean spring,
        boolean jpa,
        boolean concurrency,
        boolean test,
        Set<String> frameworks
) {
    public enum Layer { CONTROLLER, SERVICE, REPOSITORY, ENTITY, DTO, TEST, UNKNOWN }

    public String summary() {
        return """
                File: %s
                Class: %s
                Java Version: %s
                Line Count: %d
                Method Count: %d
                Longest Method: %d lines
                Layer: %s
                Detected Techs: %s
                """.formatted(fileName, className, javaVersion, lineCount, methodCount,
                longestMethodLines, layer,
                frameworks.isEmpty() ? "none" : String.join(", ", frameworks));
    }
}