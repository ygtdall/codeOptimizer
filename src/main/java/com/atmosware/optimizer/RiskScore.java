package com.atmosware.optimizer;

import java.util.List;

public class RiskScore {

    public static int of(List<Finding> findings) {
        double total = 0;
        for (Finding f : findings) {
            double weight = switch (f.severity().toUpperCase()) {
                case "HIGH" -> 10;
                case "MEDIUM" -> 4;
                default -> 1;
            };
            if (!"CONFIRMED".equalsIgnoreCase(f.confidence())) {
                weight = weight / 2;
            }
            total += weight;
        }
        return (int) Math.round(total);
    }

    public static String label(int score) {
        if (score >= 20) return "HIGH";
        if (score >= 8) return "MEDIUM";
        if (score > 0) return "LOW";
        return "CLEAN";
    }
}