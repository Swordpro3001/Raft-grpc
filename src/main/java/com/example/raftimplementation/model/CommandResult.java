package com.example.raftimplementation.model;

/**
 * Outcome of a client command submitted to a node.
 */
public enum CommandResult {
    /** A quorum stored the command and it was applied to the leader's state machine. */
    COMMITTED,
    NOT_LEADER,
    SUSPENDED,
    /** The node stopped being leader before the command committed; it may or may not survive. */
    LEADERSHIP_LOST,
    /** No quorum confirmed the command in time; it may still commit later. */
    TIMEOUT
}
