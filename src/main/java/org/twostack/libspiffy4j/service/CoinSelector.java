package org.twostack.libspiffy4j.service;

import org.twostack.libspiffy4j.model.BitcoinUtxo;
import org.twostack.libspiffy4j.model.UtxoSelectionStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class CoinSelector {

    private static final int BRANCH_AND_BOUND_MAX_ITERATIONS = 100_000;
    private static final long DUST_THRESHOLD = 546;

    public record CoinSelectionResult(List<BitcoinUtxo> selected, long totalSelected, long change) {}

    public CoinSelectionResult select(List<BitcoinUtxo> utxos, long targetSats, UtxoSelectionStrategy strategy) {
        if (utxos == null || utxos.isEmpty()) {
            throw new IllegalArgumentException("No UTXOs available for selection");
        }
        if (targetSats <= 0) {
            throw new IllegalArgumentException("Target amount must be positive");
        }

        return switch (strategy) {
            case SMALLEST_FIRST -> selectSmallestFirst(utxos, targetSats);
            case LARGEST_FIRST -> selectLargestFirst(utxos, targetSats);
            case RANDOM -> selectRandom(utxos, targetSats);
            case OPTIMAL_CHANGE -> selectOptimalChange(utxos, targetSats);
        };
    }

    private CoinSelectionResult selectSmallestFirst(List<BitcoinUtxo> utxos, long targetSats) {
        var sorted = new ArrayList<>(utxos);
        sorted.sort(Comparator.comparingLong(BitcoinUtxo::valueSats));
        return accumulate(sorted, targetSats);
    }

    private CoinSelectionResult selectLargestFirst(List<BitcoinUtxo> utxos, long targetSats) {
        var sorted = new ArrayList<>(utxos);
        sorted.sort(Comparator.comparingLong(BitcoinUtxo::valueSats).reversed());
        return accumulate(sorted, targetSats);
    }

    private CoinSelectionResult selectRandom(List<BitcoinUtxo> utxos, long targetSats) {
        var shuffled = new ArrayList<>(utxos);
        Collections.shuffle(shuffled);
        return accumulate(shuffled, targetSats);
    }

    private CoinSelectionResult selectOptimalChange(List<BitcoinUtxo> utxos, long targetSats) {
        // Try exact match within dust threshold
        for (var utxo : utxos) {
            long diff = utxo.valueSats() - targetSats;
            if (diff >= 0 && diff <= DUST_THRESHOLD) {
                return new CoinSelectionResult(List.of(utxo), utxo.valueSats(), diff);
            }
        }

        // Try branch-and-bound for minimal change
        var sorted = new ArrayList<>(utxos);
        sorted.sort(Comparator.comparingLong(BitcoinUtxo::valueSats).reversed());

        var bestResult = branchAndBound(sorted, targetSats);
        if (bestResult != null) {
            return bestResult;
        }

        // Fallback to largest-first
        return selectLargestFirst(utxos, targetSats);
    }

    private CoinSelectionResult branchAndBound(List<BitcoinUtxo> sortedDesc, long targetSats) {
        int n = sortedDesc.size();
        long bestChange = Long.MAX_VALUE;
        List<BitcoinUtxo> bestSelection = null;
        long bestTotal = 0;

        int iterations = 0;
        boolean[] included = new boolean[n];
        long currentTotal = 0;
        int depth = 0;

        // Precompute suffix sums for pruning
        long[] suffixSum = new long[n + 1];
        for (int i = n - 1; i >= 0; i--) {
            suffixSum[i] = suffixSum[i + 1] + sortedDesc.get(i).valueSats();
        }

        while (depth >= 0 && iterations < BRANCH_AND_BOUND_MAX_ITERATIONS) {
            iterations++;

            if (depth == n) {
                if (currentTotal >= targetSats) {
                    long change = currentTotal - targetSats;
                    if (change < bestChange) {
                        bestChange = change;
                        bestTotal = currentTotal;
                        bestSelection = new ArrayList<>();
                        for (int i = 0; i < n; i++) {
                            if (included[i]) bestSelection.add(sortedDesc.get(i));
                        }
                        if (change == 0) break; // Perfect match
                    }
                }
                depth--;
                continue;
            }

            if (!included[depth]) {
                // Try including this UTXO
                included[depth] = true;
                currentTotal += sortedDesc.get(depth).valueSats();
                depth++;
            } else {
                // Backtrack: exclude this UTXO
                included[depth] = false;
                currentTotal -= sortedDesc.get(depth).valueSats();

                // Prune: can we still reach target with remaining?
                if (currentTotal + suffixSum[depth + 1] < targetSats) {
                    depth--;
                    if (depth >= 0) {
                        // Undo inclusion at parent
                        included[depth] = false;
                        currentTotal -= sortedDesc.get(depth).valueSats();
                        depth--;
                    }
                    continue;
                }

                depth++;
            }
        }

        if (bestSelection != null) {
            return new CoinSelectionResult(List.copyOf(bestSelection), bestTotal, bestChange);
        }
        return null;
    }

    private CoinSelectionResult accumulate(List<BitcoinUtxo> ordered, long targetSats) {
        var selected = new ArrayList<BitcoinUtxo>();
        long total = 0;
        for (var utxo : ordered) {
            selected.add(utxo);
            total += utxo.valueSats();
            if (total >= targetSats) {
                return new CoinSelectionResult(List.copyOf(selected), total, total - targetSats);
            }
        }
        throw new IllegalArgumentException(
                "Insufficient funds: available %d sats, required %d sats".formatted(total, targetSats));
    }
}
