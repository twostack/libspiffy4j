package org.twostack.libspiffy4j.service;

import org.twostack.libspiffy4j.model.InvoiceOutputSpec;

import java.util.ArrayList;
import java.util.List;

public final class UtxoSplitService {

    private static final double[] BENFORD_DISTRIBUTION = {
            0.301, 0.176, 0.125, 0.097, 0.079, 0.067, 0.058, 0.051, 0.046
    };

    public List<InvoiceOutputSpec.P2PKHOutputSpec> generateBenfordSplit(
            long totalSats, List<String> targetAddresses, long minOutputSats) {

        if (targetAddresses == null || targetAddresses.isEmpty()) {
            throw new IllegalArgumentException("Target addresses must not be empty");
        }
        if (totalSats <= 0) {
            throw new IllegalArgumentException("Total sats must be positive");
        }

        int count = targetAddresses.size();
        long[] amounts = distributeBenford(totalSats, count, minOutputSats);

        var specs = new ArrayList<InvoiceOutputSpec.P2PKHOutputSpec>(count);
        for (int i = 0; i < count; i++) {
            specs.add(new InvoiceOutputSpec.P2PKHOutputSpec(targetAddresses.get(i), amounts[i], null));
        }
        return List.copyOf(specs);
    }

    private long[] distributeBenford(long totalSats, int count, long minOutputSats) {
        long minRequired = minOutputSats * count;
        if (totalSats < minRequired) {
            throw new IllegalArgumentException(
                    "Total %d sats insufficient for %d outputs with minimum %d sats each"
                            .formatted(totalSats, count, minOutputSats));
        }

        // Distribute proportionally using Benford weights
        double[] weights = new double[count];
        double weightSum = 0;
        for (int i = 0; i < count; i++) {
            weights[i] = BENFORD_DISTRIBUTION[i % BENFORD_DISTRIBUTION.length];
            weightSum += weights[i];
        }

        long[] amounts = new long[count];
        long allocated = 0;

        for (int i = 0; i < count; i++) {
            long proportional = Math.round((weights[i] / weightSum) * totalSats);
            amounts[i] = Math.max(proportional, minOutputSats);
            allocated += amounts[i];
        }

        // Adjust rounding difference on the first (largest) output
        long diff = totalSats - allocated;
        amounts[0] += diff;

        // Ensure first output didn't go below minimum after adjustment
        if (amounts[0] < minOutputSats) {
            throw new IllegalArgumentException(
                    "Cannot distribute %d sats across %d outputs with minimum %d sats"
                            .formatted(totalSats, count, minOutputSats));
        }

        return amounts;
    }
}
