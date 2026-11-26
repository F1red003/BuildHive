package com.buildhive.common.dag;

import java.util.*;

/**
 * A generic Directed Acyclic Graph (DAG) data structure with cycle detection,
 * topological sorting (via Kahn's algorithm), and dependency hierarchy querying.
 *
 * @param <T> the node element type
 */
public class DagGraph<T> {

    private final Set<T> nodes = new LinkedHashSet<>();
    private final Map<T, Set<T>> outgoingEdges = new LinkedHashMap<>(); // parent -> children
    private final Map<T, Set<T>> incomingEdges = new LinkedHashMap<>(); // child -> parents

    /**
     * Adds a node to the graph if not already present.
     *
     * @param node the node to add
     * @return true if the node was newly added, false if already present
     */
    public boolean addNode(T node) {
        Objects.requireNonNull(node, "node must not be null");
        if (nodes.add(node)) {
            outgoingEdges.putIfAbsent(node, new LinkedHashSet<>());
            incomingEdges.putIfAbsent(node, new LinkedHashSet<>());
            return true;
        }
        return false;
    }

    /**
     * Adds a directed edge from prerequisite to dependent (from -> to).
     * Automatically adds both nodes if not already present.
     *
     * @param from the prerequisite node
     * @param to   the dependent node
     * @return true if the edge was newly added
     * @throws CyclicDependencyException if adding this edge creates an immediate self-loop
     */
    public boolean addEdge(T from, T to) {
        Objects.requireNonNull(from, "from node must not be null");
        Objects.requireNonNull(to, "to node must not be null");

        if (from.equals(to)) {
            throw CyclicDependencyException.fromPath(List.of(from, to));
        }

        addNode(from);
        addNode(to);

        boolean addedOut = outgoingEdges.get(from).add(to);
        boolean addedIn = incomingEdges.get(to).add(from);
        return addedOut || addedIn;
    }

    /**
     * Returns an unmodifiable set of all nodes in the graph.
     */
    public Set<T> getNodes() {
        return Collections.unmodifiableSet(nodes);
    }

    /**
     * Returns an unmodifiable set of direct child (dependent) nodes for the given parent node.
     */
    public Set<T> getChildren(T node) {
        Set<T> children = outgoingEdges.get(node);
        return children != null ? Collections.unmodifiableSet(children) : Collections.emptySet();
    }

    /**
     * Returns an unmodifiable set of direct parent (prerequisite) nodes for the given child node.
     */
    public Set<T> getParents(T node) {
        Set<T> parents = incomingEdges.get(node);
        return parents != null ? Collections.unmodifiableSet(parents) : Collections.emptySet();
    }

    /**
     * Returns all root nodes (nodes with zero prerequisite parents).
     */
    public Set<T> getRoots() {
        Set<T> roots = new LinkedHashSet<>();
        for (T node : nodes) {
            if (getParents(node).isEmpty()) {
                roots.add(node);
            }
        }
        return Collections.unmodifiableSet(roots);
    }

    /**
     * Returns all leaf nodes (nodes with zero dependent children).
     */
    public Set<T> getLeaves() {
        Set<T> leaves = new LinkedHashSet<>();
        for (T node : nodes) {
            if (getChildren(node).isEmpty()) {
                leaves.add(node);
            }
        }
        return Collections.unmodifiableSet(leaves);
    }

    /**
     * Computes and returns the topological ordering of the graph using Kahn's algorithm.
     *
     * @return ordered list of nodes where each node appears after all its prerequisite parents
     * @throws CyclicDependencyException if a cycle exists in the graph
     */
    public List<T> getTopologicalOrder() {
        Map<T, Integer> inDegree = new LinkedHashMap<>();
        for (T node : nodes) {
            inDegree.put(node, incomingEdges.get(node).size());
        }

        Queue<T> readyQueue = new ArrayDeque<>();
        for (T node : nodes) {
            if (inDegree.get(node) == 0) {
                readyQueue.add(node);
            }
        }

        List<T> sortedList = new ArrayList<>(nodes.size());
        while (!readyQueue.isEmpty()) {
            T current = readyQueue.poll();
            sortedList.add(current);

            for (T child : getChildren(current)) {
                int updatedInDegree = inDegree.get(child) - 1;
                inDegree.put(child, updatedInDegree);
                if (updatedInDegree == 0) {
                    readyQueue.add(child);
                }
            }
        }

        if (sortedList.size() != nodes.size()) {
            List<T> cyclePath = findCyclePath();
            throw CyclicDependencyException.fromPath(cyclePath);
        }

        return Collections.unmodifiableList(sortedList);
    }

    /**
     * Validates that the graph is a valid Directed Acyclic Graph.
     *
     * @throws CyclicDependencyException if the graph contains any cycle
     */
    public void validate() {
        getTopologicalOrder();
    }

    /**
     * Computes the depth (longest path from any root) for every node in the graph.
     * A root node has depth 0.
     *
     * @return a map of each node to its depth
     * @throws CyclicDependencyException if the graph contains a cycle
     */
    public Map<T, Integer> getNodeDepths() {
        List<T> order = getTopologicalOrder();
        Map<T, Integer> depths = new LinkedHashMap<>();

        for (T node : order) {
            Set<T> parents = getParents(node);
            if (parents.isEmpty()) {
                depths.put(node, 0);
            } else {
                int maxParentDepth = parents.stream()
                        .mapToInt(depths::get)
                        .max()
                        .orElse(0);
                depths.put(node, maxParentDepth + 1);
            }
        }

        return Collections.unmodifiableMap(depths);
    }

    /**
     * Returns the depth of a specific node (longest distance from root).
     */
    public int getDepth(T node) {
        Integer depth = getNodeDepths().get(node);
        if (depth == null) {
            throw new NoSuchElementException("Node " + node + " does not exist in graph");
        }
        return depth;
    }

    /**
     * Finds and extracts a cycle path in the graph for informative error reporting.
     */
    private List<T> findCyclePath() {
        Set<T> visited = new HashSet<>();
        Set<T> recursionStack = new HashSet<>();
        List<T> path = new ArrayList<>();

        for (T node : nodes) {
            if (findCycleDfs(node, visited, recursionStack, path)) {
                // Trim path to start and end with the cycle node
                T cycleStart = path.getLast();
                int startIndex = path.indexOf(cycleStart);
                return new ArrayList<>(path.subList(startIndex, path.size()));
            }
        }

        // Fallback in case DFS doesn't isolate simple cycle
        return new ArrayList<>(nodes);
    }

    private boolean findCycleDfs(T current, Set<T> visited, Set<T> recursionStack, List<T> path) {
        if (recursionStack.contains(current)) {
            path.add(current);
            return true;
        }
        if (visited.contains(current)) {
            return false;
        }

        visited.add(current);
        recursionStack.add(current);
        path.add(current);

        for (T child : getChildren(current)) {
            if (findCycleDfs(child, visited, recursionStack, path)) {
                return true;
            }
        }

        recursionStack.remove(current);
        path.removeLast();
        return false;
    }
}
