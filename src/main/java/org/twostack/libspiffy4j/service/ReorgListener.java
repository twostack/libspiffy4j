package org.twostack.libspiffy4j.service;

import org.twostack.libspiffy4j.model.ReorgResult;

/**
 * Callback interface for reorg event notifications.
 */
@FunctionalInterface
public interface ReorgListener {
    void onReorganization(ReorgResult result);
}
