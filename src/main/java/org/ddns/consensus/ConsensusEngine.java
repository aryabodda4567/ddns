package org.ddns.consensus;

import org.ddns.bc.Block;
import org.ddns.bc.Transaction;
import org.ddns.db.BlockDb;
import org.ddns.db.DBUtil;
import org.ddns.db.TransactionDb;
import org.ddns.net.Message;
import org.ddns.net.MessageHandler;
import org.ddns.node.NodesManager;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ConsensusEngine implements MessageHandler {

    private static final Set<Transaction> transactions =
            ConcurrentHashMap.newKeySet();

    private static final long BLOCK_BUFFER_TIME = 15000;

    private final LivenessController livenessController;

    public void publishTransaction(Transaction transaction) {

        if (transaction == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }

        // Verify before accepting
        if (!transaction.verifySignature(transaction.getSenderPublicKey())) {
            ConsolePrinter.printWarning("Rejected invalid transaction signature");
            return;
        }

        // Add to local mempool
        transactions.add(transaction);

        // Broadcast to network
        Transaction.publish(transaction);

        ConsolePrinter.printInfo("Transaction published: " + transaction.getHash());
    }


    private static class Holder {
        private static final ConsensusEngine INSTANCE = new ConsensusEngine();
    }

    public static ConsensusEngine getInstance() {
        return Holder.INSTANCE;
    }

    public ConsensusEngine() {
        this.livenessController = new LivenessController(BLOCK_BUFFER_TIME);
    }

    // ================= Scheduler =================

    public static void runRound() {
        ConsensusEngine engine = getInstance();

        engine.livenessController.checkLiveness(ConsensusSystem.getScheduler());

        QueueNode leader = CircularQueue.getInstance().peek();
        if (leader == null) return;

        boolean isSelfLeader =
                leader.nodeConfig.equals(DBUtil.getInstance().getSelfNode());

        if (!isSelfLeader) return;
        if (transactions.isEmpty()) return;

        ConsolePrinter.printInfo("I am leader. Producing block...");
        engine.publishBlock();
    }

    // ================= Network =================

    @Override
    public void onDirectMessage(String message) {
        Message msg = ConversionUtil.fromJson(message, Message.class);

        switch (msg.type) {
            case TRANSACTION_PUBLISH -> appendTransaction(msg);
            case BLOCK_PUBLISH -> onBlockReceived(msg);
        }
    }

    @Override public void onBroadcastMessage(String message) {}
    @Override public void onMulticastMessage(String message) {}

    // ================= Logic =================

    private void appendTransaction(Message message) {
        ConsolePrinter.printInfo("[ConsensusEngine] Transaction received");
        Transaction tx = ConversionUtil.fromJson(message.payload, Transaction.class);
        if (!tx.verifySignature(tx.getSenderPublicKey())) return;
        transactions.add(tx);
    }

    private void onBlockReceived(Message message) {
        ConsolePrinter.printInfo("[ConsensusEngine] Block received");
        Block block = ConversionUtil.fromJson(message.payload, Block.class);

//        if (BlockDb.getInstance().hasBlock(block.getHash())) return;

        String latest = BlockDb.getInstance().getLatestBlockHash();
        if (!block.getPreviousHash().equals(latest)) {
            ConsolePrinter.printWarning("Rejected block: wrong previous hash");
            return;
        }

        boolean inserted = BlockDb.getInstance().insertBlock(block);

        if (!inserted) {
            return; // duplicate block, ignore
        }

        for (Transaction tx : block.getTransactions()) {
            TransactionDb.getInstance().insertTransaction(List.of(tx));
        }

        NodesManager.applyBlock(true);
        transactions.clear();

        CircularQueue.getInstance().rotate();
        livenessController.resetTimer();
    }

    public void publishBlock() {
        ConsolePrinter.printInfo("[ConsensusEngine] Publishing block");
        Block block = new Block(
                BlockDb.getInstance().getLatestBlockHash(),
                new ArrayList<>(transactions)
        );

        Block.publish(block);


        boolean inserted = BlockDb.getInstance().insertBlock(block);

        if (inserted) {
            for (Transaction tx : block.getTransactions()) {
                TransactionDb.getInstance().insertTransaction(List.of(tx));
            }


            NodesManager.applyBlock(true);
        }
        transactions.clear();

        CircularQueue.getInstance().rotate();
        livenessController.resetTimer();
    }

    public static boolean hasNoPendingTransactions() {
        return transactions.isEmpty();
    }
}
