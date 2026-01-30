package org.ddns.tests;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.bc.Block;
import org.ddns.bc.Transaction;
import org.ddns.bc.TransactionType;
import org.ddns.chain.Wallet;
import org.ddns.constants.FileNames;
import org.ddns.db.BlockDb;
import org.ddns.db.TransactionDb;
import org.ddns.dns.DNSModel;
import org.ddns.net.NetworkManager;
import org.ddns.node.NodesManager;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;
import org.ddns.util.TimeUtil;

import java.security.KeyPair;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Creates a chain of 35 blocks, each containing multiple transactions.
 * Displays all block and transaction properties in an ordered and readable format.
 */
public class BlockChainBuilder {

    public static void main(String[] args) {
        Security.addProvider(new BouncyCastleProvider());

        try {


            final int BLOCK_COUNT = 500;
            final int TXS_PER_BLOCK = 5;

            List<Block> chain = new ArrayList<>();
            String previousHash = "0"; // Genesis block previous hash

            System.out.println("=== Building Blockchain with " + BLOCK_COUNT + " Blocks ===\n");

            for (int i = 0; i < BLOCK_COUNT; i++) {
                List<Transaction> transactions = new ArrayList<>();

                // --- Create dummy transactions for this block ---
                for (int j = 0; j < TXS_PER_BLOCK; j++) {
                    KeyPair keyPair = Wallet.getKeyPair();

                    String name = "node" + i + "-tx" + j + ".example.com.";
                    int type = (j % 2 == 0) ? 1 : 16;
                    long ttl = 3600 + (i * 10);
                    String rdata = (type == 1) ? ("192.168." + i + "." + j)
                            : ("v=spf1 include:spf.example" + i + ".com");

                    DNSModel dns = new DNSModel(name, type, ttl, rdata, keyPair.getPublic(), null,TimeUtil.getCurrentUnixTime());
                    List<DNSModel> payload = List.of(dns);


                    Transaction tx = new Transaction(keyPair.getPublic(), TransactionType.REGISTER, payload,TimeUtil.getCurrentUnixTime());
                    tx.sign(keyPair.getPrivate());
                    transactions.add(tx);
                }

                // --- Create the block ---
                Block block = new Block(previousHash, transactions,TimeUtil.getCurrentUnixTime());

                BlockDb.getInstance().insertBlock(block);
                NodesManager.applyBlock(false);

                chain.add(block);
                previousHash = block.getHash();
            }

//            //NetworkManager.sendFile(NetworkUtility.getLocalIpAddress(), BlockDb.getInstance().exportSnapshot());
//
//
//            TimeUtil.waitForSeconds(2);
//            BlockDb.getInstance().truncateDatabase(false);
//
//
//            System.out.println("Hash after deletion: " + BlockDb.getInstance().getLatestBlockHash());
//
//            List<String> blockStrings = BlockDb.getInstance().extractInsertStatementsFromDbFile(FileNames.BLOCK_DB_TEMP);
//
//
//            for (String block : blockStrings) {
//                BlockDb.getInstance().executeInsertSQL(block);
//                List<Transaction> transactions = Objects.requireNonNull(BlockDb.getInstance().readBlockByHash(
//                        BlockDb.getInstance().getLatestBlockHash()
//                )).getTransactions();
//                TransactionDb.getInstance().insertTransaction(transactions);
//            }
//
//
//            chain.clear();
//
//            String lastHash = BlockDb.getInstance().getLatestBlockHash();
//            System.out.println(lastHash);
//
//            while (true) {
//                assert lastHash != null;
//                if (lastHash.equals("0")) break;
//                Block block = BlockDb.getInstance().readBlockByHash(lastHash);
////                System.out.println(block);
//                chain.add(block);
//                lastHash = block.getPreviousHash();
//            }


            // === Display chain in order ===
            System.out.println("=== Blockchain Details ===\n");

//            for (int i = 0; i < chain.size(); i++) {
//                Block block = chain.get(i);
//
//                System.out.println("----------------------------------------------------");
//                System.out.println("Block #" + i);
//                System.out.println("----------------------------------------------------");
//                System.out.println("Block Hash       : " + block.getHash());
//                System.out.println("Previous Hash    : " + block.getPreviousHash());
//                System.out.println("Merkle Root      : " + block.getMerkleRoot());
//                System.out.println("Timestamp        : " + block.getTimestamp());
//                System.out.println("Transaction Count: " + block.getTransactions().size());
//                System.out.println();
//
//                int txIndex = 0;
//                for (Transaction tx : block.getTransactions()) {
//                    System.out.println("  --- Transaction #" + txIndex + " ---");
//                    System.out.println("  Hash         : " + tx.getHash());
//                    System.out.println("  Type         : " + tx.getType());
//                    System.out.println("  Timestamp    : " + tx.getTimestamp());
//                    System.out.println("  Sender PubKey: " +
//                            tx.getSenderPublicKey().hashCode() + " (short hash)");
//                    System.out.println("  Signature    : " +
//                            (tx.getSignature() != null ? tx.getSignature().length + " bytes" : "null"));
//                    System.out.println("  Payload:");
//
//                    for (DNSModel record : tx.getPayload()) {
//                        System.out.println("     ▪ Name           : " + record.getName());
//                        System.out.println("     ▪ Type           : " + record.getType());
//                        System.out.println("     ▪ TTL            : " + record.getTtl());
//                        System.out.println("     ▪ RDATA          : " + record.getRdata());
//                        System.out.println("     ▪ Owner PubKey   : " + record.getOwner().hashCode());
//                        System.out.println("     ▪ TransactionHash: " + record.getTransactionHash());
//                        System.out.println("     ▪ Timestamp      : " + record.getTimestamp());
//                    }
//                    txIndex++;
//                    System.out.println();
//                }
//
//                // JSON representation (optional)
//                System.out.println("  JSON Representation:");
//                System.out.println(ConversionUtil.toJson(block));
//                System.out.println();
//            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
