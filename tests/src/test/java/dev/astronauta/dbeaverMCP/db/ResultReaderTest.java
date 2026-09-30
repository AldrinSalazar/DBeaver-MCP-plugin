package dev.astronauta.dbeaverMCP.db;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

public class ResultReaderTest {
    @Test
    public void exactlyTheLimitIsCompleteWhenNoExtraRowExists() throws Exception {
        var result = ResultReader.read(DbFakes.rows(1, 2), 2);
        assertEquals(false, result.get("truncated"));
        assertEquals(List.of(List.of(1), List.of(2)), result.get("rows"));
    }

    @Test
    public void sizeBudgetReturnsOnlyCompleteRowsWithAnExplicitReason() throws Exception {
        var result = ResultReader.read(DbFakes.rows("ok", "x".repeat(1000)), 100, 150);
        assertEquals(1, result.get("rowCount"));
        assertEquals(true, result.get("truncated"));
        assertEquals("response-size-limit", result.get("truncationReason"));
    }
}
