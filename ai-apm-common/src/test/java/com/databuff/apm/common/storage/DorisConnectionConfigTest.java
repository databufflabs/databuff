package com.databuff.apm.common.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DorisConnectionConfigTest {

    @Test
    void feSingleHostUsesQueryAndHttpPorts() {
        DorisConnectionConfig cfg = new DorisConnectionConfig("doris-fe", 9030, 8030);
        assertThat(cfg.jdbcUrl("databuff")).isEqualTo(
                "jdbc:mysql://doris-fe:9030/databuff?useSSL=false&allowPublicKeyRetrieval=true");
        assertThat(cfg.feJdbcEndpoints()).containsExactly(new DorisHttpEndpoint("doris-fe", 9030));
        assertThat(cfg.feHttpEndpoints()).containsExactly(new DorisHttpEndpoint("doris-fe", 8030));
        assertThat(cfg.feHosts()).containsExactly("doris-fe");
    }

    @Test
    void feMultiHostSharedPorts() {
        DorisConnectionConfig cfg = new DorisConnectionConfig("fe1, fe2,fe3", 9030, 8030);
        assertThat(cfg.jdbcUrl("databuff"))
                .isEqualTo("jdbc:mysql:loadbalance://fe1:9030,fe2:9030,fe3:9030/databuff"
                        + "?useSSL=false&allowPublicKeyRetrieval=true");
        assertThat(cfg.feHttpEndpoints()).containsExactly(
                new DorisHttpEndpoint("fe1", 8030),
                new DorisHttpEndpoint("fe2", 8030),
                new DorisHttpEndpoint("fe3", 8030));
    }

    @Test
    void feHostPortListOverridesPerEntry() {
        DorisConnectionConfig cfg = new DorisConnectionConfig("fe1:19030,fe2:19031", 9030, 8030);
        assertThat(cfg.feJdbcEndpoints()).containsExactly(
                new DorisHttpEndpoint("fe1", 19030),
                new DorisHttpEndpoint("fe2", 19031));
        // Same host:port string applies to HTTP path (explicit ports win over httpPort default).
        assertThat(cfg.feHttpEndpoints()).containsExactly(
                new DorisHttpEndpoint("fe1", 19030),
                new DorisHttpEndpoint("fe2", 19031));
        assertThat(cfg.jdbcUrl("databuff"))
                .startsWith("jdbc:mysql:loadbalance://fe1:19030,fe2:19031/databuff");
    }

    @Test
    void feMixedHostAndHostPortUsesDefaultWhereOmitted() {
        DorisConnectionConfig cfg = new DorisConnectionConfig("fe1,fe2:19031", 9030, 8030);
        assertThat(cfg.feJdbcEndpoints()).containsExactly(
                new DorisHttpEndpoint("fe1", 9030),
                new DorisHttpEndpoint("fe2", 19031));
        assertThat(cfg.feHttpEndpoints()).containsExactly(
                new DorisHttpEndpoint("fe1", 8030),
                new DorisHttpEndpoint("fe2", 19031));
    }

    @Test
    void feSingleHostWithEmbeddedPort() {
        DorisConnectionConfig cfg = new DorisConnectionConfig("fe1:19030", 9030, 8030);
        assertThat(cfg.jdbcUrl("databuff")).isEqualTo(
                "jdbc:mysql://fe1:19030/databuff?useSSL=false&allowPublicKeyRetrieval=true");
        assertThat(cfg.feHttpEndpoints()).containsExactly(new DorisHttpEndpoint("fe1", 19030));
    }

    @Test
    void beSingleHostUsesBeHttpPort() {
        DorisConnectionConfig cfg = new DorisConnectionConfig(
                "ai-apm-doris-fe", 9030, 8030, "ai-apm-doris-be", 8040);
        assertThat(cfg.preferDirectBeStreamLoad()).isTrue();
        assertThat(cfg.beHttpEndpoints()).containsExactly(new DorisHttpEndpoint("ai-apm-doris-be", 8040));
        assertThat(cfg.effectiveBeHttpHost()).isEqualTo("ai-apm-doris-be");
        assertThat(cfg.effectiveBeHttpPort()).isEqualTo(8040);
    }

    @Test
    void beMultiHostSharedPort() {
        DorisConnectionConfig cfg = new DorisConnectionConfig(
                "fe", 9030, 8030, "be1,be2,be3", 8040);
        assertThat(cfg.beHttpEndpoints()).containsExactly(
                new DorisHttpEndpoint("be1", 8040),
                new DorisHttpEndpoint("be2", 8040),
                new DorisHttpEndpoint("be3", 8040));
    }

    @Test
    void beHostPortListAndMixedDefaults() {
        DorisConnectionConfig cfg = new DorisConnectionConfig(
                "fe", 9030, 8030, "be1,be2:8041", 8040);
        assertThat(cfg.beHttpEndpoints()).containsExactly(
                new DorisHttpEndpoint("be1", 8040),
                new DorisHttpEndpoint("be2", 8041));
        assertThat(cfg.effectiveBeHttpHost()).isEqualTo("be1");
        assertThat(cfg.effectiveBeHttpPort()).isEqualTo(8040);
    }

    @Test
    void beSingleHostWithEmbeddedPort() {
        DorisConnectionConfig cfg = new DorisConnectionConfig(
                "fe", 9030, 8030, "be1:18040", 8040);
        assertThat(cfg.beHttpEndpoints()).containsExactly(new DorisHttpEndpoint("be1", 18040));
        assertThat(cfg.effectiveBeHttpPort()).isEqualTo(18040);
    }

    @Test
    void unsetBeDisablesDirectStreamLoad() {
        DorisConnectionConfig cfg = new DorisConnectionConfig("fe", 9030, 8030, null, 8040);
        assertThat(cfg.preferDirectBeStreamLoad()).isFalse();
        assertThat(cfg.beHttpEndpoints()).isEmpty();
        assertThat(cfg.effectiveBeHttpHost()).isEqualTo("fe");
        assertThat(cfg.effectiveBeHttpPort()).isEqualTo(8040);
    }

    @Test
    void fromEnvUsesDefaults() {
        DorisConnectionConfig cfg = DorisConnectionConfig.fromEnv();
        assertThat(cfg.feHost()).isNotBlank();
        assertThat(cfg.queryPort()).isPositive();
        assertThat(cfg.httpPort()).isPositive();
    }
}
