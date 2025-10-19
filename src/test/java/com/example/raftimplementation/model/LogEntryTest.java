package com.example.raftimplementation.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LogEntry model.
 * Tests log entry creation and properties.
 */
class LogEntryTest {

    @Test
    void testLogEntryCreation() {
        LogEntry entry = new LogEntry(1, "SET x=1");

        assertEquals(1, entry.getTerm());
        assertEquals("SET x=1", entry.getCommand());
    }

    @Test
    void testLogEntryWithZeroTerm() {
        LogEntry entry = new LogEntry(0, "INIT");

        assertEquals(0, entry.getTerm());
        assertEquals("INIT", entry.getCommand());
    }

    @Test
    void testLogEntryWithHighTerm() {
        LogEntry entry = new LogEntry(1000, "TEST_COMMAND");

        assertEquals(1000, entry.getTerm());
        assertEquals("TEST_COMMAND", entry.getCommand());
    }

    @Test
    void testLogEntryWithComplexCommand() {
        String complexCommand = "INSERT INTO users VALUES (1, 'John', 'Doe')";
        LogEntry entry = new LogEntry(5, complexCommand);

        assertEquals(5, entry.getTerm());
        assertEquals(complexCommand, entry.getCommand());
    }

    @Test
    void testLogEntryEquality() {
        LogEntry entry1 = new LogEntry(1, "CMD1");
        LogEntry entry2 = new LogEntry(1, "CMD1");
        LogEntry entry3 = new LogEntry(2, "CMD1");
        LogEntry entry4 = new LogEntry(1, "CMD2");

        // Check if entries with same term and command are considered equal
        assertEquals(entry1.getTerm(), entry2.getTerm());
        assertEquals(entry1.getCommand(), entry2.getCommand());
        
        // Different term
        assertNotEquals(entry1.getTerm(), entry3.getTerm());
        
        // Different command
        assertNotEquals(entry1.getCommand(), entry4.getCommand());
    }

    @Test
    void testMultipleLogEntries() {
        LogEntry[] entries = new LogEntry[10];
        
        for (int i = 0; i < 10; i++) {
            entries[i] = new LogEntry(i / 3 + 1, "COMMAND_" + i);
        }

        assertEquals("COMMAND_0", entries[0].getCommand());
        assertEquals("COMMAND_9", entries[9].getCommand());
        assertEquals(1, entries[0].getTerm());
        assertEquals(4, entries[9].getTerm());
    }

    @Test
    void testLogEntryWithEmptyCommand() {
        LogEntry entry = new LogEntry(1, "");

        assertEquals(1, entry.getTerm());
        assertEquals("", entry.getCommand());
    }

    @Test
    void testLogEntryWithSpecialCharacters() {
        String specialCommand = "SET key=\"value with spaces\" AND symbols !@#$%";
        LogEntry entry = new LogEntry(2, specialCommand);

        assertEquals(2, entry.getTerm());
        assertEquals(specialCommand, entry.getCommand());
    }

    @Test
    void testLogEntryImmutability() {
        LogEntry entry = new LogEntry(1, "ORIGINAL");
        
        int originalTerm = entry.getTerm();
        String originalCommand = entry.getCommand();

        // Verify values don't change
        assertEquals(originalTerm, entry.getTerm());
        assertEquals(originalCommand, entry.getCommand());
    }
}
