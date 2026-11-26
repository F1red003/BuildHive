package com.buildhive.common.dag;

import java.util.Collections;
import java.util.List;

/**
 * Exception thrown when a cycle is detected in a Directed Acyclic Graph.
 */
public class CyclicDependencyException extends RuntimeException {

    private final List<?> cyclePath;

    public CyclicDependencyException(String message, List<?> cyclePath) {
        super(message);
        this.cyclePath = cyclePath != null ? Collections.unmodifiableList(cyclePath) : Collections.emptyList();
    }

    public List<?> getCyclePath() {
        return cyclePath;
    }

    public static CyclicDependencyException fromPath(List<?> cyclePath) {
        String pathString = cyclePath.stream()
                .map(Object::toString)
                .reduce((a, b) -> a + " -> " + b)
                .orElse("unknown");
        return new CyclicDependencyException("Cycle detected in graph dependency path: " + pathString, cyclePath);
    }
}
