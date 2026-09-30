package dev.astronauta.dbeaverMCP.db;

import java.io.Reader;
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
        return render(value, 0);
    }

    private static Object render(Object value, int depth) {
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
                long length = Math.min(clob.length(), MAX_VALUE_CHARS + 1L);
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
                long length = blob.length();
                Map<String, Object> map = new LinkedHashMap<>();
                if (length <= 65536) {
                    map.putAll(renderBytes(blob.getBytes(1, (int) length)));
                } else {
                    map.put("binary", "<" + length + " bytes, too large to inline>");
                }
                map.put("byteLength", length);
                map.put("truncated", length > 65536);
                return map;
            } catch (Exception e) {
                return "<unreadable BLOB: " + e.getMessage() + ">";
            }
        }
        if (value instanceof SQLXML xml) {
            try (Reader reader = xml.getCharacterStream()) {
                return boundedText(reader);
            } catch (Exception e) {
                return "<unreadable XML: " + e.getMessage() + ">";
            }
        }
        if (value instanceof Array array) {
            if (depth > 0) {
                return "<nested ARRAY omitted>";
            }
            try (var elements = array.getResultSet()) {
                List<Object> items = new ArrayList<>();
                while (elements.next()) {
                    if (items.size() == 100) {
                        items.add("<... truncated after 100 elements>");
                        break;
                    }
                    items.add(render(elements.getObject(2), depth + 1));
                }
                return items;
            } catch (Exception e) {
                return "<unreadable ARRAY: " + e.getMessage() + ">";
            }
        }
        return truncate(String.valueOf(value), MAX_VALUE_CHARS);
    }

    private static String boundedText(Reader reader) throws Exception {
        char[] buffer = new char[MAX_VALUE_CHARS + 1];
        int length = 0;
        while (length < buffer.length) {
            int count = reader.read(buffer, length, buffer.length - length);
            if (count < 0) {
                break;
            }
            length += count;
        }
        String text = new String(buffer, 0, Math.min(length, MAX_VALUE_CHARS));
        return length > MAX_VALUE_CHARS ? text + "...<truncated>" : text;
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
