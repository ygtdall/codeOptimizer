package com.atmosware.optimizer;

import java.util.List;

public record FileResult(
        String className,
        String fileName,
        CodeContext.Layer layer,
        int findingCount,
        int score,
        String htmlName,
        List<Finding> findings
) { }