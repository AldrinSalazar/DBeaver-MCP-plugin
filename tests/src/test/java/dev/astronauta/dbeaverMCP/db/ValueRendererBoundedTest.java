package dev.astronauta.dbeaverMCP.db;

import static org.junit.Assert.*;

import java.io.Reader;
import java.sql.Array;
import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.SQLXML;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ValueRendererBoundedTest {
    @Test
    public void xmlReadsOnlyTheBoundedPrefixAndClosesTheStream() {
        int[] characters = {0};
        boolean[] closed = {false};
        Reader reader = new Reader() {
            public int read(char[] target, int offset, int length) {
                Arrays.fill(target, offset, offset + length, 'x');
                characters[0] += length;
                return length;
            }
            public void close() { closed[0] = true; }
        };
        SQLXML xml = DbFakes.proxy(SQLXML.class, (name, args) ->
            name.equals("getCharacterStream") ? reader : DbFakes.unexpected(name));
        String rendered = (String) ValueRenderer.render(xml);
        assertTrue(rendered.endsWith("<truncated>"));
        assertEquals(ValueRenderer.MAX_VALUE_CHARS + 1, characters[0]);
        assertTrue(closed[0]);
    }

    @Test
    public void arraysStreamOnlyOneHundredElementsAndDetectAnExtraElement() {
        int[] visited = {0};
        boolean[] closed = {false};
        ResultSet rows = DbFakes.proxy(ResultSet.class, (name, args) -> switch (name) {
            case "next" -> ++visited[0] < 10000;
            case "getObject" -> "value";
            case "close" -> { closed[0] = true; yield null; }
            default -> DbFakes.unexpected(name);
        });
        Array array = DbFakes.proxy(Array.class, (name, args) ->
            name.equals("getResultSet") ? rows : DbFakes.unexpected(name));
        List<?> rendered = (List<?>) ValueRenderer.render(array);
        assertEquals(101, rendered.size());
        assertEquals(101, visited[0]);
        assertTrue(rendered.getLast().toString().contains("truncated"));
        assertTrue(closed[0]);
    }

    @Test
    public void largeBlobsDoNotMaterializeBytesAndReportTheirActualLength() {
        Blob blob = DbFakes.proxy(Blob.class, (name, args) ->
            name.equals("length") ? 10_000_000L : DbFakes.unexpected(name));
        Map<?, ?> rendered = (Map<?, ?>) ValueRenderer.render(blob);
        assertEquals(10_000_000L, rendered.get("byteLength"));
        assertEquals(true, rendered.get("truncated"));
    }
}
