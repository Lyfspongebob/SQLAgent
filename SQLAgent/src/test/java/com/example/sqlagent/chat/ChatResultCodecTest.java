package com.example.sqlagent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatResultCodecTest {

    private final ChatResultCodec codec =
            new ChatResultCodec(new ObjectMapper().findAndRegisterModules());

    @Test
    void roundTripsStructuredResults() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("name", "Alice");

        String serialized = codec.serialize(List.of(row));
        ChatResultCodec.DecodedSqlResults decoded = codec.deserialize(serialized);

        assertEquals("json", decoded.format());
        assertEquals(List.of(row), decoded.rows());
        assertNull(decoded.rawText());
    }

    @Test
    void keepsLegacyTextInsteadOfFailingHistory() {
        ChatResultCodec.DecodedSqlResults decoded =
                codec.deserialize("[{id=1, name=Alice}]");

        assertEquals("legacy_text", decoded.format());
        assertNull(decoded.rows());
        assertEquals("[{id=1, name=Alice}]", decoded.rawText());
    }

    @Test
    void sanitizesBinaryValues() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("payload", new byte[]{1, 2, 3});

        String serialized = codec.serialize(List.of(row));
        ChatResultCodec.DecodedSqlResults decoded = codec.deserialize(serialized);

        assertEquals("[binary 3 bytes]", decoded.rows().getFirst().get("payload"));
    }

    @Test
    void handlesMissingResults() {
        assertNull(codec.serialize(null));

        ChatResultCodec.DecodedSqlResults decoded = codec.deserialize(null);
        assertNull(decoded.rows());
        assertNull(decoded.rawText());
        assertNull(decoded.format());
    }
}
