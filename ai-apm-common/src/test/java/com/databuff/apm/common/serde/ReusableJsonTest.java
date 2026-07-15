package com.databuff.apm.common.serde;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReusableJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void reusesBufferAcrossCalls() throws Exception {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("x", 1);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("y", "z");

        byte[] first = ReusableJson.writeValueAsBytes(MAPPER, a);
        byte[] second = ReusableJson.writeValueAsBytes(MAPPER, b);

        assertThat(first).isEqualTo(MAPPER.writeValueAsBytes(a));
        assertThat(second).isEqualTo(MAPPER.writeValueAsBytes(b));
        assertThat(first).isNotEqualTo(second);
    }
}
