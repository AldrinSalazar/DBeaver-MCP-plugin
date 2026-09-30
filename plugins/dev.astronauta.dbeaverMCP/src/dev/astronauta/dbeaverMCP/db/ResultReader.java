package dev.astronauta.dbeaverMCP.db;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.astronauta.dbeaverMCP.server.Json;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCResultSet;

/** Converts result rows with a row cap and a budget for serialized column/row data. */
final class ResultReader {
    static final int MAX_RESULT_BYTES = 2 * 1024 * 1024;

    private record Row(List<Object> values, long bytes) {
    }

    private ResultReader() {
    }

    static Map<String, Object> empty() {
        return Map.of("columns", List.of(), "rows", List.of(), "rowCount", 0, "truncated", false);
    }

    static Map<String, Object> read(DBCResultSet source, int limit) throws Exception {
        return read(source, limit, MAX_RESULT_BYTES);
    }

    static Map<String, Object> read(DBCResultSet source, int limit, int maxBytes) throws Exception {
        if (source == null) {
            return empty();
        }
        List<? extends DBCAttributeMetaData> attributes = source.getMeta().getAttributes();
        List<Map<String, Object>> columns = new ArrayList<>();
        for (DBCAttributeMetaData attribute : attributes) {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", attribute.getName());
            column.put("label", attribute.getLabel());
            column.put("type", attribute.getTypeName());
            columns.add(column);
        }
        long bytes = jsonBytes(columns) + 2; // Row array brackets.
        if (bytes > maxBytes) {
            throw new BridgeException("Result column metadata exceeds the response size limit.");
        }
        List<List<Object>> rows = new ArrayList<>();
        String truncationReason = null;
        while (source.nextRow()) {
            if (rows.size() == limit) {
                truncationReason = "row-limit";
                break;
            }
            int commaBytes = rows.isEmpty() ? 0 : 1;
            Row row = readRow(source, attributes.size(), maxBytes - bytes - commaBytes);
            if (row == null) {
                truncationReason = "response-size-limit";
                break;
            }
            rows.add(row.values());
            bytes += row.bytes() + commaBytes;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("rows", rows);
        result.put("rowCount", rows.size());
        result.put("truncated", truncationReason != null);
        if (truncationReason != null) {
            result.put("truncationReason", truncationReason);
        }
        return result;
    }

    /** A null row means its next cell would exceed the remaining budget. */
    private static Row readRow(DBCResultSet source, int columnCount, long remainingBytes) {
        List<Object> row = new ArrayList<>(columnCount);
        long bytes = 2;
        for (int column = 0; column < columnCount; column++) {
            Object value;
            try {
                value = source.getAttributeValue(column);
            } catch (Exception e) {
                value = "<unreadable: " + e.getMessage() + ">";
            }
            Object rendered = ValueRenderer.render(value);
            bytes += jsonBytes(rendered) + (column == 0 ? 0 : 1);
            if (bytes > remainingBytes) {
                return null;
            }
            row.add(rendered);
        }
        return new Row(row, bytes);
    }

    private static long jsonBytes(Object value) {
        return Json.stringify(value).getBytes(StandardCharsets.UTF_8).length;
    }
}
