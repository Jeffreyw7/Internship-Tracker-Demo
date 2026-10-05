package com.campus.tracker.support;

import org.testcontainers.containers.GenericContainer;

/** Real Redis only. A supplied port must point to a disposable local test server. */
public final class RedisFixture implements AutoCloseable {
    private final GenericContainer<?> container;
    private final String host;
    private final int port;

    public RedisFixture() {
        String supplied = System.getProperty("campus.test.redis.port");
        if (supplied != null) {
            container = null;
            host = "127.0.0.1";
            port = Integer.parseInt(supplied);
            if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid disposable Redis port");
        } else {
            container = new GenericContainer<>("redis:7.4.11-alpine").withExposedPorts(6379)
                    .withCommand("redis-server", "--maxmemory", "64mb", "--maxmemory-policy", "noeviction");
            container.start();
            host = container.getHost();
            port = container.getMappedPort(6379);
        }
    }
    public String host() { return host; }
    public int port() { return port; }
    public void stop() {
        if (container == null) throw new IllegalStateException("Outage scenario requires isolated container ownership");
        container.stop();
    }
    @Override public void close() { if (container != null && container.isRunning()) container.stop(); }
}
