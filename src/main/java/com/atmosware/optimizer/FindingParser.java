package com.atmosware.optimizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public class FindingParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<Finding> parse(String answer) throws Exception {
        String json = clean(answer);
        return mapper.readValue(json, new TypeReference<List<Finding>>() {});
    }

    private String clean(String answer) {
        String t = answer.trim();
        int start = t.indexOf('[');
        int end = t.lastIndexOf(']');
        if (start == -1 || end == -1) throw new IllegalStateException("JSON dizisi bulunamadı");
        return t.substring(start, end + 1);
    }
}