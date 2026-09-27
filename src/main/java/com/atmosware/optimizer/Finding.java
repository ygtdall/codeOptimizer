package com.atmosware.optimizer;

public record Finding(
        int line,
        String method,
        String severity,
        String confidence,
        String title,
        String detail
) { }