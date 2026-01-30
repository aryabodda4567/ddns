package org.ddns.tests;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.Main;
import org.ddns.bc.Transaction;
import org.ddns.bc.TransactionType;
import org.ddns.chain.Wallet;
import org.ddns.consensus.ConsensusEngine;
import org.ddns.db.BlockDb;
import org.ddns.db.TransactionDb;
import org.ddns.dns.DNSModel;
import org.ddns.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple single-threaded data test for TransactionDb.
 * - Creates a few wallets
 * - Builds simple transactions with small DNSModel payloads
 * - Inserts them to TransactionDb
 * - Reads them back and prints a short summary
 * <p>
 * Console-only, no frameworks.
 */
public class TransactionDbTest {

    private static final Logger log = LoggerFactory.getLogger(TransactionDbTest.class);

    public static void main(String[] args) {
        Security.addProvider(new BouncyCastleProvider());

       // System.out.println(TransactionDb.getInstance().readTransactionByHash("822ac459d68b40206d4a808b61ffd680e6765631c3084fd6b20631d429cb6cd3"));
        System.out.println(BlockDb.getInstance().readBlockByHash("e2c400901367c57d18678230984546b76c78f3b2c5c216657837345cd3f513d2"));
     //   System.out.println(BlockDb.getInstance().readBlockByHash("5c83e7b8ca3485dbdf493629a97b61ca63bb5d4940475ae4a584e5bb79eecdd6"));

//        System.out.println("=== TransactionDbSimpleTest START ===");
//        Security.addProvider(new BouncyCastleProvider());
//        TransactionDb txDb = TransactionDb.getInstance();
//
//
////         Optional: start clean if you want (uncomment)
////         txDb.dropDatabase();
//        txDb = TransactionDb.getInstance();
//
//
//        System.out.println(txDb.readTransactionByHash("70deac44cb12f1f0cc56789d675368294847827a970efc7894c866cf5d49d41e"));
//        TransactionDb.TransactionRow row = txDb.readTransactionByHash("70deac44cb12f1f0cc56789d675368294847827a970efc7894c866cf5d49d41e");
//
//        if (row == null) {
//            System.out.println("❌ No transaction found for that hash.");
//        } else {
//            System.out.println("=== Transaction Details ===");
//            System.out.println("Hash:       " + row.txHash);
//            System.out.println("Sender:     " + row.sender);
//            System.out.println("Type:       " + row.type);
//            System.out.println("Timestamp:  " + row.timestamp);
//            System.out.println("Payload:    " + (row.payloadJson != null && row.payloadJson.length() > 200
//                    ? row.payloadJson.substring(0, 200) + "..."
//                    : row.payloadJson));
//            System.out.println("Signature:  " + (row.signature != null
//                    ? "(hex len=" + row.signature.length * 2 + ")"
//                    : "null"));
//            System.out.println("============================");
//        }
//
//
//        // Create simple test data: 5 wallets, each creates 2 transactions
//        int wallets = 5;
//        int txPerWallet = 2;
//
//        for (int i = 0; i < wallets; i++) {
//            try {
//                KeyPair kp = Wallet.getKeyPair();
//                PublicKey pub = kp.getPublic();
//                PrivateKey priv = kp.getPrivate();
//
//                for (int t = 0; t < txPerWallet; t++) {
//                    List<DNSModel> payload = new ArrayList<>();
//                    String hostname = "test" + i + "-" + t + ".example.com";
//                    String rdata = "10.0." + i + "." + (100 + t);
//                    // DNSModel constructor: (name, type, ttl, rdata, ownerPubKey, transactionHash)
//                    payload.add(new DNSModel(hostname, 1, 300L, rdata, pub, null));
//
//                    Transaction tx = new Transaction(pub, TransactionType.REGISTER, payload);
//                    tx.sign(priv);
//
////                    boolean inserted = txDb.insertTransaction(
////                            tx.getHash(),
////                            tx.getSenderPublicKey(),
////                            tx.getType(),
////                            tx.getPayload(),
////                            tx.getSignature()
////                    );
////                    System.out.println(tx.getHash());
////
////                    System.out.println("Inserted tx: hash=" + tx.getHash() + " inserted=" + inserted);
//                    System.out.println(tx);
//                }
//            } catch (Exception e) {
//                System.err.println("Wallet/tx creation failed: " + e.getMessage());
//                e.printStackTrace();
//            }
//        }
//
//        // Read back a sample of transactions (we'll attempt to read the last created tx hashes)
//        System.out.println("\n--- Reading back inserted transactions (sample) ---");
//        // Note: TransactionDb doesn't provide a listAll for transactions in the simple implementation.
//        // We'll demonstrate reading a single known hash by re-creating one example hash.
//        // For simplicity, attempt to read a hash by constructing a new tx like above and then reading it.
//
//        try {
//            KeyPair kp = Wallet.getKeyPair();
//            PublicKey pub = kp.getPublic();
//            PrivateKey priv = kp.getPrivate();
//
//            List<DNSModel> payload = new ArrayList<>();
//            payload.add(new DNSModel("sample-read.example.com", 1, 300L, "10.1.2.3", pub, null));
//            Transaction tx = new Transaction(pub, TransactionType.REGISTER, payload);
//            tx.sign(priv);
//
//            boolean inserted = txDb.insertTransaction(tx.getHash(), tx.getSenderPublicKey(), tx.getType(), tx.getPayload(), tx.getSignature());
//            System.out.println("Inserted sample-read tx: " + inserted);
//
//            TransactionDb.TransactionRow row1 = txDb.readTransactionByHash(tx.getHash());
//            if (row1 != null) {
//                System.out.println("Read tx row:");
//                System.out.println("  tx_hash: " + row1.txHash);
//                System.out.println("  sender : " + row1.sender);
//                System.out.println("  type   : " + row1.type);
//                System.out.println("  ts     : " + row1.timestamp + " (" + row1.timestamp + ")");
//                System.out.println("  payload(json): " + (row1.payloadJson == null ? "<null>" : (row1.payloadJson.length() > 200 ? row1.payloadJson.substring(0, 200) + "..." : row1.payloadJson)));
//            } else {
//                System.out.println("Failed to read the sample transaction by hash.");
//            }
//        } catch (Exception e) {
//            System.err.println("Sample read test failed: " + e.getMessage());
//            e.printStackTrace();
//        }
//
//        System.out.println("=== TransactionDbSimpleTest END ===");
    }

    public static void testTransaction(){
        Transaction transaction = new Transaction(
                Wallet.getKeyPair().getPublic(),
                TransactionType.REGISTER,
                new ArrayList<>(),
                TimeUtil.getCurrentUnixTime()
        );
        ConsensusEngine consensusEngine = new ConsensusEngine();
        consensusEngine.publishTransaction(transaction);
    }
}
