package org.twostack.libspiffy4j.service;

import java.util.List;

/**
 * Functional interface for looking up transaction IDs associated with an address.
 * The host application provides the implementation (e.g., via a blockchain indexer API).
 */
@FunctionalInterface
public interface AddressLookupFunction {

    /**
     * Returns the list of transaction IDs associated with the given address.
     *
     * @param address a Bitcoin address (P2PKH)
     * @return list of transaction IDs (hex strings), empty if no transactions found
     * @throws Exception if the lookup fails
     */
    List<String> lookup(String address) throws Exception;
}
