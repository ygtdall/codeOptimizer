package com.atmosware.optimizer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskScoreTest {

    private Finding finding(String severity, String confidence) {
        return new Finding(1, "m", severity, confidence, "t", "d");
    }

    @Test
    void returnsZeroWhenThereAreNoFindings() {
        assertEquals(0, RiskScore.of(List.of()));
        assertEquals("CLEAN", RiskScore.label(0));
    }

    @Test
    void sumsSeverityWeights() {
        int score = RiskScore.of(List.of(
                finding("HIGH", "CONFIRMED"),
                finding("MEDIUM", "CONFIRMED"),
                finding("LOW", "CONFIRMED")));

        assertEquals(15, score);
    }

    @Test
    void halvesTheWeightOfInferredFindings() {
        assertEquals(5, RiskScore.of(List.of(finding("HIGH", "INFERRED"))));
    }

    @Test
    void mapsScoresToLabels() {
        assertEquals("LOW", RiskScore.label(4));
        assertEquals("MEDIUM", RiskScore.label(10));
        assertEquals("HIGH", RiskScore.label(25));
    }
}