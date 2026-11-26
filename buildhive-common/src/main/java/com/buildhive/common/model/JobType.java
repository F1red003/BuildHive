package com.buildhive.common.model;

/**
 * Defines the distinct stages of a Maven Java build pipeline.
 */
public enum JobType {

    /**
     * Verifies the uploaded archive structure, Maven POM existence, coordinates, and security constraints.
     */
    PROJECT_VALIDATION(
            "Project Validation",
            1,
            100,
            FailureClassification.USER_CODE_ERROR
    ),

    /**
     * Resolves and downloads external Maven dependencies into the local cache.
     */
    DEPENDENCY_RESOLUTION(
            "Dependency Resolution",
            2,
            90,
            FailureClassification.INFRASTRUCTURE_ERROR
    ),

    /**
     * Compiles Java source files into bytecode classes.
     */
    COMPILATION(
            "Compilation",
            3,
            80,
            FailureClassification.USER_CODE_ERROR
    ),

    /**
     * Executes JUnit test suites and records test reports.
     */
    UNIT_TESTS(
            "Unit Tests",
            4,
            70,
            FailureClassification.USER_CODE_ERROR
    ),

    /**
     * Performs static analysis using tools like Checkstyle and SpotBugs.
     */
    STATIC_ANALYSIS(
            "Static Analysis",
            4,
            70,
            FailureClassification.USER_CODE_ERROR
    ),

    /**
     * Packages compiled classes and resources into an executable or library JAR artifact.
     */
    PACKAGING(
            "Packaging",
            5,
            60,
            FailureClassification.USER_CODE_ERROR
    ),

    /**
     * Aggregates execution metrics, logs, test outputs, and artifacts into a consolidated build report.
     */
    REPORT_GENERATION(
            "Report Generation",
            6,
            50,
            FailureClassification.INFRASTRUCTURE_ERROR
    );

    private final String displayName;
    private final int stageOrder;
    private final int priority;
    private final FailureClassification defaultFailureClassification;

    JobType(String displayName, int stageOrder, int priority, FailureClassification defaultFailureClassification) {
        this.displayName = displayName;
        this.stageOrder = stageOrder;
        this.priority = priority;
        this.defaultFailureClassification = defaultFailureClassification;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getStageOrder() {
        return stageOrder;
    }

    public int getPriority() {
        return priority;
    }

    public FailureClassification getDefaultFailureClassification() {
        return defaultFailureClassification;
    }

    /**
     * Indicates whether a failure at this stage is retryable by default.
     */
    public boolean isFailureRetryableByDefault() {
        return this.defaultFailureClassification.isRetryable();
    }
}
