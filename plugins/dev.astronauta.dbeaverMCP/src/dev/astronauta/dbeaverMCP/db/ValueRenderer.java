package dev.astronauta.dbeaverMCP.db;

import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLXML;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jkiss.dbeaver.model.data.DBDValue;

/**
 * Converts raw database values into JSON-safe data (maps, lists, strings,
 * numbers, booleans, null). Large values are truncated, never inlined whole.
 */
public final class ValueRenderer {

    public static final int MAX_VALUE_CHARS = 10_000;
    public static final long MAX_LOB_BYTES = 1024 * 1024;

    private ValueRenderer() {
    }

    public static Object render(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof DBDValue dbValue && dbValue.isNull()) {
            return null;
        }
        if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
            return null;
        }
        if (value instanceof Float f && (f.isNaN() || f.isInfinite())) {
            return null;
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof String s) {
            return truncate(s, MAX_VALUE_CHARS);
        }
        if (value instanceof byte[] bytes) {
            return renderBytes(bytes);
        }
        if (value instanceof java.util.Date date) {
            return Instant.ofEpochMilli(date.getTime()).toString();
        }
        if (value instanceof java.time.temporal.TemporalAccessor temporal) {
            return truncate(temporal.toString(), MAX_VALUE_CHARS);
        }
        if (value instanceof Clob clob) {
            try {
                long length = Math.min(clob.length(), MAX_LOB_BYTES);
                String text = clob.getSubString(1, (int) length);
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("clob", truncate(text, MAX_VALUE_CHARS));
                map.put("truncated", clob.length() > length || text.length() > MAX_VALUE_CHARS);
                return map;
            } catch (Exception e) {
                return "<unreadable CLOB: " + e.getMessage() + ">";
            }
        }
        if (value instanceof Blob blob) {
            try {
                long length = Math.min(blob.length(), MAX_LOB_BYTES);
                byte[] bytes = blob.getBytes(1, (int) length);
                Map<String, Object> map = new LinkedHashMap<>(renderBytes(bytes));
                map.put("truncated", blob.length() > length);
                return map;
            } catch (Exception e) {
                return "<unreadable BLOB: " + e.getMessage() + ">";
            }
        }
        if (value instanceof SQLXML xml) {
            try {
                return truncate(String.valueOf(xml.getString()), MAX_VALUE_CHARS);
            } catch (Exception e) {
                return "<unreadable XML: " + e.getMessage() + ">";
            }
        }
        if (value instanceof Array array) {
            try {
                Object raw = array.getArray();
                List<Object> items = new ArrayList<>();
                if (raw instanceof Object[] objects) {
                    for (int i = 0; i < objects.length && i < 100; i++) {
                        items.add(render(objects[i]));
                    }
                    if (objects.length > 100) {
                        items.add("<... " + (objects.length - 100) + " more>");
                    }
                } else {
                    items.add(truncate(String.valueOf(raw), MAX_VALUE_CHARS));
                }
                return items;
            } catch (Exception e) {
                return "<unreadable ARRAY: " + e.getMessage() + ">";
            }
        }
        return truncate(String.valueOf(value), MAX_VALUE_CHARS);
    }

    static String truncate(String value, int max) {
        if (value != null && value.length() > max) {
            return value.substring(0, max) + "...<truncated, " + value.length() + " chars total>";
        }
        return value;
    }

    private static Map<String, Object> renderBytes(byte[] bytes) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (bytes.length > 65536) {
            map.put("binary", "<" + bytes.length + " bytes, too large to inline>");
        } else {
            map.put("base64", Base64.getEncoder().encodeToString(bytes));
        }
        map.put("byteLength", bytes.length);
        return map;
    }
}
