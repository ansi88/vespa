// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.config.provision.CloudAccount;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.autoscale.ResourceChange;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A specification of a set of nodes.
 * This reflects that nodes can be requested either by count and flavor or by type,
 * and encapsulates the differences in logic between these two cases.
 *
 * @author bratseth
 */
public interface NodeSpec {

    /** The node type this requests */
    NodeType type();

    /** Returns whether the given node resources is compatible with this spec */
    boolean isCompatible(NodeResources resources);

    /** Returns whether the given node count is sufficient to consider this spec fulfilled to the maximum amount */
    boolean saturatedBy(int count);

    /** Returns whether the given node count is sufficient to fulfill this spec */
    default boolean fulfilledBy(int count) {
        return fulfilledDeficitCount(count) == 0;
    }

    /** Returns the total number of nodes this is requesting, or empty if not specified */
    Optional<Integer> count();

    int groups();

    /** Returns the group size requested if count() is present. Throws RuntimeException otherwise. */
    default int groupSize() { return count().get() / groups(); }

    /** Returns whether this should throw an exception if the requested nodes are not fully available */
    boolean canFail();

    /** Returns whether we should retire nodes at all when fulfilling this spec */
    boolean considerRetiring();

    /** Returns number of additional nodes needed for this spec to be fulfilled given the current node count */
    int fulfilledDeficitCount(int count);

    /** Returns the resources requested by this or empty if none are explicitly requested */
    Optional<NodeResources> resources();

    /** Returns whether the given node must be resized to match this spec */
    boolean needsResize(Node node);

    /** Returns true if there exist some circumstance where we may accept to have this node allocated */
    boolean acceptable(NodeCandidate node);

    /** Returns true if nodes with non-active parent hosts should be rejected */
    boolean rejectNonActiveParent();

    /** Returns the cloud account to use when fulfilling this spec */
    CloudAccount cloudAccount();

    /** Returns the host TTL to use for any hosts provisioned as a result of this fulfilling this spec. */
    default Duration hostTTL() { return Duration.ZERO; }

    /**
     * Returns true if a node with given current resources and current spare host resources can be resized
     * in-place to resources in this spec.
     */
    default boolean canResize(NodeResources currentNodeResources, NodeResources currentSpareHostResources,
                              ClusterSpec.Type type, boolean hasTopologyChange, int currentClusterSize) {
        return false;
    }

    static NodeSpec from(int nodeCount, int groupCount, NodeResources resources, boolean exclusive, boolean canFail,
                         CloudAccount cloudAccount, Duration hostTTL) {
        return new CountNodeSpec(nodeCount, groupCount, resources, exclusive, canFail, canFail, cloudAccount, hostTTL);
    }

    static NodeSpec from(NodeType type, CloudAccount cloudAccount) {
        return new TypeNodeSpec(type, cloudAccount);
    }

    /** A node spec specifying a node count and a flavor */
    class CountNodeSpec implements NodeSpec {

        private final int count;
        private final int groups;
        private final NodeResources requestedNodeResources;
        private final boolean exclusive;
        private final boolean canFail;
        private final boolean considerRetiring;
        private final CloudAccount cloudAccount;
        private final Duration hostTTL;

        private CountNodeSpec(int count, int groups, NodeResources resources, boolean exclusive, boolean canFail,
                              boolean considerRetiring, CloudAccount cloudAccount, Duration hostTTL) {
            this.count = count;
            this.groups = groups;
            this.requestedNodeResources = Objects.requireNonNull(resources, "Resources must be specified");
            this.exclusive = exclusive;
            this.canFail = canFail;
            this.considerRetiring = considerRetiring;
            this.cloudAccount = Objects.requireNonNull(cloudAccount);
            this.hostTTL = Objects.requireNonNull(hostTTL);

            if (!canFail && considerRetiring)
                throw new IllegalArgumentException("Cannot consider retiring nodes if we cannot fail");
        }

        @Override
        public Optional<Integer> count() { return Optional.of(count); }

        @Override
        public int groups() { return groups; }

        @Override
        public Optional<NodeResources> resources() {
            return Optional.of(requestedNodeResources);
        }

        @Override
        public NodeType type() { return NodeType.tenant; }

        @Override
        public boolean isCompatible(NodeResources resources) {
            return resources.equalsWhereSpecified(requestedNodeResources);
        }

        @Override
        public boolean saturatedBy(int count) { return fulfilledBy(count); } // min=max for count specs

        @Override
        public boolean canFail() { return canFail; }

        @Override
        public boolean considerRetiring() {
            return considerRetiring;
        }

        @Override
        public int fulfilledDeficitCount(int count) {
            return Math.max(this.count - count, 0);
        }

        public NodeSpec withoutRetiring() {
            return new CountNodeSpec(count, groups, requestedNodeResources, exclusive, canFail, false, cloudAccount, hostTTL);
        }

        @Override
        public boolean needsResize(Node node) {
            return ! node.resources().equalsWhereSpecified(requestedNodeResources);
        }

        @Override
        public boolean canResize(NodeResources currentNodeResources, NodeResources currentSpareHostResources,
                                 ClusterSpec.Type type, boolean hasTopologyChange, int currentClusterSize) {
            return ResourceChange.canInPlaceResize(currentClusterSize, currentNodeResources, count, requestedNodeResources,
                                                   type, exclusive, hasTopologyChange)
                   &&
                   currentSpareHostResources.add(currentNodeResources.justNumbers()).satisfies(requestedNodeResources);

        }

        @Override
        public boolean acceptable(NodeCandidate node) { return true; }

        @Override
        public boolean rejectNonActiveParent() {
            return false;
        }

        @Override
        public CloudAccount cloudAccount() {
            return cloudAccount;
        }

        @Override
        public Duration hostTTL() { return hostTTL; }

        @Override
        public String toString() {
            return "request for " + count + " nodes" +
                   ( groups > 1 ? " (in " + groups + " groups)" : "") +
                   " with " + requestedNodeResources; }

    }

    /** A node spec specifying a node type. */
    class TypeNodeSpec implements NodeSpec {

        private static final Map<NodeType, Integer> WANTED_NODE_COUNT = Map.of(NodeType.config, 3,
                                                                               NodeType.controller, 3);

        private final NodeType type;
        private final CloudAccount cloudAccount;

        private TypeNodeSpec(NodeType type, CloudAccount cloudAccount) {
            this.type = type;
            this.cloudAccount = cloudAccount;
        }

        @Override
        public Optional<Integer> count() { return Optional.empty(); }

        @Override
        public int groups() { return 1; }

        @Override
        public NodeType type() { return type; }

        @Override
        public boolean isCompatible(NodeResources resources) { return true; }

        @Override
        public boolean saturatedBy(int count) { return false; }

        @Override
        public boolean canFail() { return false; }

        @Override
        public boolean considerRetiring() { return true; }

        @Override
        public int fulfilledDeficitCount(int count) {
            // If no wanted count is specified for this node type, then any count fulfills the deficit
            return Math.max(0, WANTED_NODE_COUNT.getOrDefault(type, 0) - count);
        }

        @Override
        public Optional<NodeResources> resources() {
            return Optional.empty();
        }

        @Override
        public boolean needsResize(Node node) { return false; }

        @Override
        public boolean acceptable(NodeCandidate node) {
            // Since we consume all offered nodes we should not accept previously deactivated nodes
            return node.state() != Node.State.inactive;
        }

        @Override
        public boolean rejectNonActiveParent() {
            return true;
        }

        @Override
        public CloudAccount cloudAccount() {
            return cloudAccount;
        }

        @Override
        public String toString() { return "request for nodes of type '" + type + "'"; }

    }

}
