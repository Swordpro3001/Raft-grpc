package com.example.raftimplementation.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClusterConfigurationTest {

    private static ClusterConfiguration simple(String... servers) {
        return ClusterConfiguration.createNew(Set.of(servers), Map.of());
    }

    @Test
    void oddClusterNeedsMajority() {
        ClusterConfiguration config = simple("a", "b", "c", "d", "e");

        assertFalse(config.hasQuorum(List.of("a", "b")));
        assertTrue(config.hasQuorum(List.of("a", "b", "c")));
    }

    @Test
    void evenClusterNeedsMoreThanHalf() {
        ClusterConfiguration config = simple("a", "b", "c", "d", "e", "f");

        assertFalse(config.hasQuorum(List.of("a", "b", "c")));
        assertTrue(config.hasQuorum(List.of("a", "b", "c", "d")));
    }

    @Test
    void serversOutsideTheConfigurationDoNotCount() {
        assertFalse(simple("a", "b", "c").hasQuorum(List.of("a", "x", "y")));
    }

    @Test
    void jointConfigurationNeedsMajorityOfBothSides() {
        ClusterConfiguration joint = ClusterConfiguration.createJoint(
            Set.of("a", "b", "c"), Set.of("c", "d", "e"), Map.of());

        assertFalse(joint.hasQuorum(List.of("a", "b")), "no majority of the new servers");
        assertFalse(joint.hasQuorum(List.of("d", "e")), "no majority of the old servers");
        assertTrue(joint.hasQuorum(List.of("c", "d", "a")));
    }

    @Test
    void quorumIndexIsStoredOnAMajority() {
        ClusterConfiguration config = simple("a", "b", "c");

        assertEquals(5, config.quorumIndex(Map.of("a", 9, "b", 5, "c", 1)));
        assertEquals(-1, config.quorumIndex(Map.of("a", 9)));
    }

    @Test
    void quorumIndexOfEvenClusterNeedsMoreThanHalf() {
        ClusterConfiguration config = simple("a", "b", "c", "d");

        assertEquals(3, config.quorumIndex(Map.of("a", 9, "b", 7, "c", 3, "d", 1)));
    }

    @Test
    void jointQuorumIndexIsTheLowerOfBothSides() {
        ClusterConfiguration joint = ClusterConfiguration.createJoint(
            Set.of("a", "b", "c"), Set.of("a", "b", "c", "d"), Map.of());

        // Old side: 7 is on a majority. New side: 3 of 4 is only reached at 2.
        assertEquals(2, joint.quorumIndex(Map.of("a", 9, "b", 7, "c", 2, "d", 0)));
    }

    @Test
    void finalConfigurationForgetsAddressesOfRemovedServers() {
        ServerInfo a = new ServerInfo("a", "h", 1, 2);
        ServerInfo b = new ServerInfo("b", "h", 3, 4);

        ClusterConfiguration config = ClusterConfiguration.createNew(Set.of("a"), Map.of("a", a, "b", b));

        assertEquals(Map.of("a", a), config.getAddresses());
    }
}
