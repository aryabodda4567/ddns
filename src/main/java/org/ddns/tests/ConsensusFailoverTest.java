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
import java.util.Objects;

public class ConsensusFailoverTest {

    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        System.out.println("=== CONSENSUS FAILOVER TEST START ===");

        // ---------------- Setup nodes ----------------
        CircularQueue queue = CircularQueue.getInstance();

        NodeConfig node1 = new NodeConfig("192.168.0.1", Role.GENESIS, Wallet.getKeyPair().getPublic());
        NodeConfig node2 = new NodeConfig("192.168.0.2", Role.LEADER_NODE, Wallet.getKeyPair().getPublic());
        NodeConfig node3 = new NodeConfig("192.168.0.3", Role.NORMAL_NODE, Wallet.getKeyPair().getPublic());

        queue.addNode(new QueueNode(node1, 1));
        queue.addNode(new QueueNode(node2, 2));
        queue.addNode(new QueueNode(node3, 3));

        System.out.println("Initial Leader: " + Objects.requireNonNull(queue.peek()).getNodeConfig().getIp());

        // ---------------- Create real transaction ----------------
        KeyPair kp = Wallet.getKeyPair();

        Transaction tx = new Transaction(
                kp.getPublic(),
                TransactionType.REGISTER,
                new ArrayList<>()
        );
        tx.sign(kp.getPrivate());

        // Publish transaction into consensus
        ConsensusEngine.getInstance().publishTransaction(tx);

        System.out.println("Transaction published. Mempool size > 0");

        // ---------------- Start consensus ----------------
        ConsensusSystem.start();

        // ---------------- Simulate leader-1 failure ----------------
        System.out.println("Simulating leader-1 failure (no block production)...");

        // Wait longer than BLOCK_BUFFER_TIME (15s)
        Thread.sleep(20000);

        System.out.println("Leader after timeout: " + Objects.requireNonNull(queue.peek()).getNodeConfig().getIp());

        // ---------------- Now leader-2 should produce block ----------------
        System.out.println("Waiting for leader-2 to produce block...");

        Thread.sleep(5000);

        String latestHash = BlockDb.getInstance().getLatestBlockHash();
        System.out.println("Latest block hash: " + latestHash);

        System.out.println("Current Leader Now: " + Objects.requireNonNull(queue.peek()).getNodeConfig().getIp());

        System.out.println("=== TEST END ===");
    }
}
