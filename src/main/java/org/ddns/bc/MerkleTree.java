package org.ddns.bc;

import java.util.ArrayList;
import java.util.List;

/**
 * A simple utility to generate a Merkle Root from a list of transactions.
 * This is essential for enabling blockchain pruning.
 */
public class MerkleTree {

    /**
     * Generates the Merkle Root for a given list of transactions.
     * @param transactions The list of transactions to include in the tree.
     * @return The Merkle Root hash as a string.
     */
    public static String getMerkleRoot(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return SignatureUtil.applySha256(""); // An empty root
        }

        List<String> treeLayer = new ArrayList<>();
        for (Transaction tx : transactions) {
            treeLayer.add(tx.getHash());
        }

        while (treeLayer.size() > 1) {
            treeLayer = getNextTreeLayer(treeLayer);
        }

        return treeLayer.get(0);
    }

    /**
     * A helper function to process one layer of the Merkle Tree.
     * It takes a list of hashes, pairs them up, hashes the pairs,
     * and returns the new list of hashes for the layer above.
     */
    private static List<String> getNextTreeLayer(List<String> currentLayer) {
        List<String> nextLayer = new ArrayList<>();
        // If there's an odd number of hashes, duplicate the last one
        if (currentLayer.size() % 2 == 1) {
            currentLayer.add(currentLayer.get(currentLayer.size() - 1));
        }

        for (int i = 0; i < currentLayer.size(); i += 2) {
            String combinedHash = SignatureUtil.applySha256(
                    currentLayer.get(i) + currentLayer.get(i + 1)
            );
            nextLayer.add(combinedHash);
        }
        return nextLayer;
    }
}