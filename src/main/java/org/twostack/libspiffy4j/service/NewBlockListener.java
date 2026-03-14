package org.twostack.libspiffy4j.service;

/**
 * Callback for block arrival notifications. Allows the host application to bridge
 * P2P block announcements into libspiffy4j without introducing a direct dependency
 * on the P2P layer.
 */
@FunctionalInterface
public interface NewBlockListener {

    void onNewBlock(long blockHeight, String blockHash);
}
