package dev.astronauta.dbeaverMCP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

import dev.astronauta.dbeaverMCP.db.ValueRenderer;
import org.junit.Test;

public class ValueRendererTest {

    @Test
    @SuppressWarnings("unchecked")
    public void rendersCommonTypes() {
        assertNull(ValueRenderer.render(null));
        assertEquals("a", ValueRenderer.render("a"));
        assertEquals(42, ValueRenderer.render(42));
        assertEquals(Boolean.TRUE, ValueRenderer.render(true));
        assertEquals(new BigDecimal("1.5"), ValueRenderer.render(new BigDecimal("1.5")));
        assertEquals("1970-01-01T00:00:00Z", ValueRenderer.render(new Date(0)));
        assertNull(ValueRenderer.render(Double.NaN));
        assertNull(ValueRenderer.render(Double.POSITIVE_INFINITY));
        assertNull(ValueRenderer.render(Float.NaN));

        Map<String, Object> binary = (Map<String, Object>) ValueRenderer.render(new byte[]{1, 2, 3});
        assertEquals("AQID", binary.get("base64"));
        assertEquals(3, binary.get("byteLength"));

        String longString = "x".repeat(ValueRenderer.MAX_VALUE_CHARS + 10);
        String rendered = (String) ValueRenderer.render(longString);
        assertTrue(rendered.endsWith(" chars total>"));
        assertTrue(rendered.length() < longString.length() + 40);
    }
}
