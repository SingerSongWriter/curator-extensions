package com.bazaarvoice.zookeeper;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class ZooKeeperConfigurationTest {
    private ZooKeeperConfiguration _config = new ZooKeeperConfiguration();

    @Test
    public void testOneAttemptBoundedExponentialBackoffRetry() {
        _config.withBoundedExponentialBackoffRetry(10, 100, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeInitialSleepTime() {
        _config.withBoundedExponentialBackoffRetry(-1, 10, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testZeroInitialSleepTime() {
        _config.withBoundedExponentialBackoffRetry(0, 10, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeMaxSleepTime() {
        _config.withBoundedExponentialBackoffRetry(10, -1, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testZeroMaxSleepTime() {
        _config.withBoundedExponentialBackoffRetry(10, 0, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeAttemptsBoundedExponentialBackoffRetry() {
        _config.withBoundedExponentialBackoffRetry(10, 100, -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testZeroAttemptsBoundedExponentialBackoffRetry() {
        _config.withBoundedExponentialBackoffRetry(10, 100, 0);
    }

    @Test
    public void testNullNamespace() {
        _config.withNamespace(null);
    }

    @Test
    public void testEmptyNamespace() {
        _config.withNamespace("");
    }

    @Test
    public void testRootNamespace() {
        _config.withNamespace("/");
    }

    @Test
    public void testNamespace() {
        _config.withNamespace("/parent");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRelativeNamespace() {
        _config.withNamespace("parent");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTrailingSlashNamespace() {
        _config.withNamespace("/parent/");
    }

    @Test(expected = NullPointerException.class)
    public void testNoConnectString() {
        _config.connect();
    }

    @Test
    public void testWithConnectString() throws IOException {
        _config.withConnectString("test.connect.string:2181");
        assertEquals("test.connect.string:2181", _config.getConnectString());
    }
}