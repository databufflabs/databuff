package com.databuff.apm.common.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DorisHttpEndpointTest {

    @Test
    void parseListSupportsHostOnlyAndHostPort() {
        List<DorisHttpEndpoint> eps = DorisHttpEndpoint.parseList("be1, be2:8041,be3", 8040);
        assertThat(eps).containsExactly(
                new DorisHttpEndpoint("be1", 8040),
                new DorisHttpEndpoint("be2", 8041),
                new DorisHttpEndpoint("be3", 8040));
    }

    @Test
    void parseListBlankYieldsEmpty() {
        assertThat(DorisHttpEndpoint.parseList(null, 8040)).isEmpty();
        assertThat(DorisHttpEndpoint.parseList("  ", 8040)).isEmpty();
        assertThat(DorisHttpEndpoint.parseList(",,", 8040)).isEmpty();
    }

    @Test
    void rejectsBlankHost() {
        assertThatThrownBy(() -> new DorisHttpEndpoint(" ", 8040))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
