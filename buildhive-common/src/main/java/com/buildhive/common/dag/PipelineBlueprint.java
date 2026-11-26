package com.buildhive.common.dag;

import com.buildhive.common.model.JobType;

import java.util.Set;

/**
 * Factory and template blueprint for constructing build pipeline dependency graphs.
 */
public final class PipelineBlueprint {

    private PipelineBlueprint() {
        // Utility class
    }

    /**
     * Constructs the standard 7-stage Maven pipeline Directed Acyclic Graph as defined in BuildHive specifications:
     * <pre>
     * Project validation
     *         ↓
     * Dependency resolution
     *         ↓
     *    Compilation
     *     ↙       ↘
     * Unit tests   Static analysis
     *     ↘       ↙
     *     Packaging
     *         ↓
     *  Report generation
     * </pre>
     *
     * @return fully validated DagGraph of JobType
     */
    public static DagGraph<JobType> createDefaultMavenPipeline() {
        DagGraph<JobType> graph = new DagGraph<>();

        // Add all 7 stages
        for (JobType jobType : JobType.values()) {
            graph.addNode(jobType);
        }

        // Define dependency edges (prerequisite -> dependent)
        graph.addEdge(JobType.PROJECT_VALIDATION, JobType.DEPENDENCY_RESOLUTION);
        graph.addEdge(JobType.DEPENDENCY_RESOLUTION, JobType.COMPILATION);

        // Parallel branches after compilation
        graph.addEdge(JobType.COMPILATION, JobType.UNIT_TESTS);
        graph.addEdge(JobType.COMPILATION, JobType.STATIC_ANALYSIS);

        // Convergence before packaging
        graph.addEdge(JobType.UNIT_TESTS, JobType.PACKAGING);
        graph.addEdge(JobType.STATIC_ANALYSIS, JobType.PACKAGING);

        // Final summary report
        graph.addEdge(JobType.PACKAGING, JobType.REPORT_GENERATION);

        // Self-validation
        graph.validate();

        return graph;
    }

    /**
     * Returns the pair of stages that can execute concurrently once compilation completes.
     */
    public static Set<JobType> getParallelVerificationStages() {
        return Set.of(JobType.UNIT_TESTS, JobType.STATIC_ANALYSIS);
    }
}
