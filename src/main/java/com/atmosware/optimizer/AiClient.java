package com.atmosware.optimizer;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;

public class AiClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final long FIRST_WAIT_MS = 2000;

    private final Client client = new Client();

    public String ask(String systemPrompt, String userPrompt) {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .temperature(0.1f)
                .maxOutputTokens(8000)
                .build();

        RuntimeException last = null;
        long wait = FIRST_WAIT_MS;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                GenerateContentResponse response = client.models.generateContent(
                        "gemini-3.1-flash-lite",
                        systemPrompt + "\n\n" + userPrompt,
                        config
                );
                return response.text();
            } catch (RuntimeException e) {
                last = e;
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS) throw e;

                System.out.println("  model meşgul, " + (wait / 1000) + " sn sonra tekrar denenecek ("
                        + attempt + "/" + (MAX_ATTEMPTS - 1) + ")");
                sleep(wait);
                wait = wait * 2;
            }
        }
        throw last;
    }

    private boolean isRetryable(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toUpperCase();
        return message.contains("503")
                || message.contains("UNAVAILABLE")
                || message.contains("OVERLOADED")
                || message.contains("429")
                || message.contains("RESOURCE_EXHAUSTED");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}