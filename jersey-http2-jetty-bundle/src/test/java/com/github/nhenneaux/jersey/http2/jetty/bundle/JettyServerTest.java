package com.github.nhenneaux.jersey.http2.jetty.bundle;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.jetty.connector.JettyClientProperties;
import org.glassfish.jersey.jetty.connector.JettyHttp2Connector;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import java.security.KeyStore;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.nhenneaux.jersey.http2.jetty.bundle.JettyServer.TlsSecurityConfiguration.getKeyStore;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("squid:S00112")
class JettyServerTest {
    static final int PORT = 2223;
    private static final String PING = "/ping";

    private static WebTarget getClient(int port, KeyStore trustStore, ClientConfig clientConfig) {
        return ClientBuilder.newBuilder()
                .trustStore(trustStore)
                .withConfig(clientConfig)
                .build()
                .target("https://localhost:" + port);
    }

    private static ClientConfig http2ClientConfig() {
        return new ClientConfig()
                .property(JettyClientProperties.ENABLE_SSL_HOSTNAME_VERIFICATION, Boolean.TRUE)
                .connectorProvider(JettyHttp2Connector::new);
    }

    static AutoCloseable jerseyServer(int port, JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration, final Class<?>... serviceClasses) {
        return new JettyServer(port, tlsSecurityConfiguration, serviceClasses);
    }

    static WebTarget getClient(int port) {
        return getClient(port, getKeyStore("TEST==ONLY==truststore-password".toCharArray(), "truststore.p12"), http2ClientConfig());
    }

    static JettyServer.TlsSecurityConfiguration tlsConfig() {
        return new JettyServer.TlsSecurityConfiguration(
                getKeyStore("TEST==ONLY==key-store-password".toCharArray(), "keystore.p12"),
                "localhost with alternate ip",
                "TEST==ONLY==key-store-password",
                "TLSv1.2"
        );
    }

    @Test
    @Timeout(20)
    void testValidTls() throws Exception {
        int port = PORT;
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            final Response ping = getClient(port).path(PING).request().head();
            assertEquals(204, ping.getStatus());
        }
    }

    @Test
    @Timeout(60)
    void testConcurrent() throws Exception {
        testConcurrent(http2ClientConfig());
    }

    @Test
    @Timeout(60)
    void testConcurrentHttp1() throws Exception {
        testConcurrent(new ClientConfig().property(JettyClientProperties.ENABLE_SSL_HOSTNAME_VERIFICATION, Boolean.TRUE));
    }


    private void testConcurrent(ClientConfig clientConfig) throws Exception {
        int port = PORT;
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        final KeyStore truststore = getKeyStore("TEST==ONLY==truststore-password".toCharArray(), "truststore.p12");
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            final int nThreads = 4;
            final int iterations = 10_000;
            // Warmup
            getClient(port, truststore, clientConfig).path(PING).request().head();

            AtomicInteger counter = new AtomicInteger();
            final Runnable runnable = () -> {
                final WebTarget client = getClient(port, truststore, clientConfig);
                final long start = System.currentTimeMillis();
                for (int i = 0; i < iterations; i++) {
                    client.path(PING).request().head();
                    counter.incrementAndGet();
                    if (i % 1_000 == 0) {
                        System.out.println((System.currentTimeMillis() - start) * 1.0 / Math.max(i, 1));
                    }
                }
            };
            Thread.setDefaultUncaughtExceptionHandler((t1, e) -> e.printStackTrace());
            final Set<Thread> threads = IntStream
                    .range(0, nThreads)
                    .mapToObj(i -> runnable)
                    .map(Thread::new)
                    .collect(Collectors.toSet());

            threads.forEach(Thread::start);


            for (Thread thread : threads) {
                thread.join();
            }

            assertEquals((long) nThreads * iterations, counter.get());

        }
    }

    @Test
    void shouldWorkInLoop() throws Exception {
        int port = PORT;
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        for (int i = 0; i < 100; i++) {
            try (
                    @SuppressWarnings("unused") WeldContainer container = new Weld().initialize();
                    AutoCloseable ignored = jerseyServer(port, tlsSecurityConfiguration, DummyRestService.class)
            ) {
                assertEquals(204, getClient(port).path(PING).request().head().getStatus());
            }
        }
    }


}