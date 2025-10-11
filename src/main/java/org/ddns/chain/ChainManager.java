package org.ddns.chain;

import org.ddns.bc.Blockchain;
import org.ddns.bc.SignatureUtil;
import org.ddns.bc.Transaction;
import org.ddns.bc.TransactionType;
import org.ddns.db.DatabaseManager;
import org.ddns.util.PersistentStorage;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

public class ChainManager {



    public static void setupGenesisNode() {
        PersistentStorage.put(Names.ROLE, Role.GENESIS + "");
    }

///
///
/// Under implementation
///
///
    public  void handleRegisterTransaction(Transaction transaction){
        TransactionType type =transaction.getType();
        if(!transaction.verifySignature()){
            System.out.println("Transaction verification failed\nHash: "+transaction.getHash()+"\n" +
                    "Public key "+ transaction.getSenderPublicKey());
            return;
        }

        DatabaseManager databaseManager = new DatabaseManager(Names.DB_FILE_NAME);
        databaseManager.logTransaction(transaction);


        switch (type){
            case REGISTER -> {
                Map<String,String> payloadMap = transaction.getPayload();
                String ip = payloadMap.get("IP");
                String domainName  = payloadMap.get("DOMAIN");
                String owner = payloadMap.get("OWNER");


            }
        }
    }

    public    void createRegisterTransaction(String domainName  , String ip, String owner ) throws Exception {
        PublicKey publicKey = SignatureUtil.getPublicKeyFromString(PersistentStorage.getString(Names.PUBLIC_KEY));

        Map<String,String> payloadMap = new HashMap<>();
        payloadMap.put("IP",ip);
        payloadMap.put("DOMAIN",domainName);
        payloadMap.put("OWNER",owner);
        Transaction transaction = new Transaction(
                publicKey,
                TransactionType.REGISTER,
                domainName,
                payloadMap
        );
        //Verify transaction
        if(!transaction.verifySignature()) {
            System.out.println("Transaction verification failed.\n");
            return;
        }
        //Sign transaction
        PrivateKey privateKey = SignatureUtil.getPrivateKeyFromString(PersistentStorage.getString(Names.PRIVATE_KEY));
        transaction.sign(privateKey);
        //Broadcast
        MessageHandler.broadcastTransaction(transaction);
    }






}
