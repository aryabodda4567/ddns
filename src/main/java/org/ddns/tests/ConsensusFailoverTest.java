package org.ddns.tests;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.bc.Transaction;
import org.ddns.bc.TransactionType;
import org.ddns.chain.Wallet;
import org.ddns.consensus.*;
import org.ddns.constants.Role;
import org.ddns.db.BlockDb;
import org.ddns.node.NodeConfig;
import org.ddns.util.TimeUtil;

import java.security.KeyPair;
import java.security.Security;
import java.util.ArrayList;

public class ConsensusFailoverTest {

    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        // ---------------- Setup nodes ----------------
        CircularQueue queue = CircularQueue.getInstance();

        NodeConfig node1 = new NodeConfig("192.168.0.1", Role.GENESIS, Wallet.getKeyPair().getPublic());
        NodeConfig node2 = new NodeConfig("192.168.0.2", Role.NONE, Wallet.getKeyPair().getPublic());
        NodeConfig node3 = new NodeConfig("192.168.0.3", Role.NONE, Wallet.getKeyPair().getPublic());

        queue.addNode(new QueueNode(node1, 1));
        queue.addNode(new QueueNode(node2, 2));
        queue.addNode(new QueueNode(node3, 3));

        // ---------------- Create real transaction ----------------
        KeyPair kp = Wallet.getKeyPair();

        Transaction tx = new Transaction(
                kp.getPublic(),
                TransactionType.REGISTER,
                new ArrayList<>(),
                TimeUtil.getCurrentUnixTime());
        tx.sign(kp.getPrivate());

        // Publish transaction into consensus
        ConsensusEngine.getInstance().publishTransaction(tx);

        // ---------------- Start consensus ----------------
        ConsensusSystem.start();

        // ---------------- Simulate leader-1 failure ----------------
        // Wait longer than BLOCK_BUFFER_TIME (15s)
        Thread.sleep(20000);

        // ---------------- Now leader-2 should produce block ----------------

        Thread.sleep(5000);
        BlockDb.getInstance().getLatestBlockHash();
    }
}
