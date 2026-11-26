package com.buildhive.common.dag;

import com.buildhive.common.model.JobType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DagGraphTest {

    @Test
    @DisplayName("Default Maven pipeline DAG constructs properly and validates without cycles")
    void testDefaultPipelineTopologicalOrder() {
        DagGraph<JobType> graph = PipelineBlueprint.createDefaultMavenPipeline();

        assertThat(graph.getNodes()).hasSize(7);
        assertThat(graph.getRoots()).containsExactly(JobType.PROJECT_VALIDATION);
        assertThat(graph.getLeaves()).containsExactly(JobType.REPORT_GENERATION);

        // Verify direct dependencies
        assertThat(graph.getChildren(JobType.COMPILATION))
                .containsExactlyInAnyOrder(JobType.UNIT_TESTS, JobType.STATIC_ANALYSIS);
        assertThat(graph.getParents(JobType.PACKAGING))
                .containsExactlyInAnyOrder(JobType.UNIT_TESTS, JobType.STATIC_ANALYSIS);

        List<JobType> order = graph.getTopologicalOrder();
        assertThat(order).hasSize(7);

        // Verify strict topological ordering constraints
        int validationIdx = order.indexOf(JobType.PROJECT_VALIDATION);
        int resolutionIdx = order.indexOf(JobType.DEPENDENCY_RESOLUTION);
        int compilationIdx = order.indexOf(JobType.COMPILATION);
        int testIdx = order.indexOf(JobType.UNIT_TESTS);
        int staticAnalysisIdx = order.indexOf(JobType.STATIC_ANALYSIS);
        int packagingIdx = order.indexOf(JobType.PACKAGING);
        int reportIdx = order.indexOf(JobType.REPORT_GENERATION);

        assertThat(validationIdx).isLessThan(resolutionIdx);
        assertThat(resolutionIdx).isLessThan(compilationIdx);
        assertThat(compilationIdx).isLessThan(testIdx);
        assertThat(compilationIdx).isLessThan(staticAnalysisIdx);
        assertThat(testIdx).isLessThan(packagingIdx);
        assertThat(staticAnalysisIdx).isLessThan(packagingIdx);
        assertThat(packagingIdx).isLessThan(reportIdx);
    }

    @Test
    @DisplayName("Unit Tests and Static Analysis have identical depth and can execute in parallel")
    void testParallelStagesHaveSameDepth() {
        DagGraph<JobType> graph = PipelineBlueprint.createDefaultMavenPipeline();
        Map<JobType, Integer> depths = graph.getNodeDepths();

        assertThat(depths.get(JobType.PROJECT_VALIDATION)).isEqualTo(0);
        assertThat(depths.get(JobType.DEPENDENCY_RESOLUTION)).isEqualTo(1);
        assertThat(depths.get(JobType.COMPILATION)).isEqualTo(2);

        // Critical requirement: UNIT_TESTS and STATIC_ANALYSIS have identical depth = 3
        assertThat(depths.get(JobType.UNIT_TESTS)).isEqualTo(3);
        assertThat(depths.get(JobType.STATIC_ANALYSIS)).isEqualTo(3);
        assertThat(graph.getDepth(JobType.UNIT_TESTS)).isEqualTo(graph.getDepth(JobType.STATIC_ANALYSIS));

        assertThat(depths.get(JobType.PACKAGING)).isEqualTo(4);
        assertThat(depths.get(JobType.REPORT_GENERATION)).isEqualTo(5);
    }

    @Test
    @DisplayName("Direct 2-node cycle is detected and throws CyclicDependencyException")
    void testDirectCycleDetection() {
        DagGraph<String> graph = new DagGraph<>();
        graph.addEdge("A", "B");
        graph.addEdge("B", "A");

        assertThatThrownBy(graph::getTopologicalOrder)
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessageContaining("Cycle detected");
    }

    @Test
    @DisplayName("Indirect 3-node cycle (A -> B -> C -> A) is detected and throws CyclicDependencyException")
    void testIndirectCycleDetection() {
        DagGraph<String> graph = new DagGraph<>();
        graph.addEdge("A", "B");
        graph.addEdge("B", "C");
        graph.addEdge("C", "A");

        assertThatThrownBy(graph::validate)
                .isInstanceOf(CyclicDependencyException.class)
                .satisfies(ex -> {
                    CyclicDependencyException cyclicEx = (CyclicDependencyException) ex;
                    assertThat(cyclicEx.getCyclePath()).isNotEmpty();
                });
    }

    @Test
    @DisplayName("Self-loop (A -> A) is immediately rejected")
    void testSelfLoopRejected() {
        DagGraph<String> graph = new DagGraph<>();
        assertThatThrownBy(() -> graph.addEdge("A", "A"))
                .isInstanceOf(CyclicDependencyException.class);
    }

    @Test
    @DisplayName("Diamond-shaped dependency graph without cycle passes validation")
    void testDiamondGraph() {
        // A -> B, A -> C, B -> D, C -> D
        DagGraph<String> graph = new DagGraph<>();
        graph.addEdge("A", "B");
        graph.addEdge("A", "C");
        graph.addEdge("B", "D");
        graph.addEdge("C", "D");

        List<String> order = graph.getTopologicalOrder();
        assertThat(order).hasSize(4);
        assertThat(order.getFirst()).isEqualTo("A");
        assertThat(order.getLast()).isEqualTo("D");
        assertThat(graph.getRoots()).containsExactly("A");
        assertThat(graph.getLeaves()).containsExactly("D");
    }

    @Test
    @DisplayName("Graph with multiple disjoint roots is correctly ordered")
    void testMultipleRootsGraph() {
        DagGraph<String> graph = new DagGraph<>();
        graph.addEdge("Root1", "Intermediate");
        graph.addEdge("Root2", "Intermediate");
        graph.addEdge("Intermediate", "Sink");

        Set<String> roots = graph.getRoots();
        assertThat(roots).containsExactlyInAnyOrder("Root1", "Root2");

        List<String> order = graph.getTopologicalOrder();
        assertThat(order.indexOf("Root1")).isLessThan(order.indexOf("Intermediate"));
        assertThat(order.indexOf("Root2")).isLessThan(order.indexOf("Intermediate"));
        assertThat(order.indexOf("Intermediate")).isLessThan(order.indexOf("Sink"));
    }
}
