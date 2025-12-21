package org.ddns.consensus;

import org.ddns.bc.Block;
import org.ddns.bc.Transaction;
import org.ddns.db.BlockDb;
import org.ddns.db.DBUtil;
import org.ddns.db.TransactionDb;
import org.ddns.net.Message;
import org.ddns.net.MessageHandler;
import org.ddns.net.NetworkManager;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.ddns.util.TimeUtil;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ConsensusEngine implements MessageHandler {
    private static final Set<Transaction> transactions = new HashSet<>();
    private static final int TRANSACTION_LIMIT=5;
    public ConsensusEngine() {
    }

    public static void runRound() {

    }

    @Override
    public void onBroadcastMessage(String message) {

    }

    @Override
    public void onDirectMessage(String message) {
        Message transactionMessage;
        if (message == null || message.isEmpty()) {
            ConsolePrinter.printWarning("[ConsensusEngine] Received null or empty direct message.");
            return;
        }
        try {
            transactionMessage = ConversionUtil.fromJson(message, Message.class);
        } catch (Exception e) {
            ConsolePrinter.printFail("[ConsensusEngine] Failed to parse incoming message: " + e.getMessage());
            return; // Can't proceed
        }

        if (transactionMessage == null || transactionMessage.type == null || transactionMessage.payload == null) {
            ConsolePrinter.printWarning("[ConsensusEngine] Received malformed message (null type or payload).");
            return;
        }

        switch (transactionMessage.type){
            case TRANSACTION_PUBLISH -> appendTransaction(transactionMessage);
            case BLOCK_PUBLISH -> commitBlock(transactionMessage);
        }

    }

    @Override
    public void onMulticastMessage(String message) {

    }

    private void appendTransaction(Message message){

        Transaction transaction =ConversionUtil.fromJson(message.payload, Transaction.class);

        if(!transaction.verifySignature(transaction.getSenderPublicKey())) return;

        transactions.add(transaction);

        if(transactions.size() == TRANSACTION_LIMIT){
            if(Objects.requireNonNull(CircularQueue.getInstance()
                    .peek()).nodeConfig.
                    equals(DBUtil.getInstance().getSelfNode())){
                TimeUtil.waitForSeconds(1);
                publishBlock();
            }

        }

        System.out.println("Transaction received: "+ transaction);


    }

    public void publishTransaction(Transaction transaction){
        transactions.add(transaction);
        Transaction.publish(transaction);

        if(transactions.size() == TRANSACTION_LIMIT){
            if(Objects.requireNonNull(CircularQueue.getInstance()
                            .peek()).nodeConfig.
                    equals(DBUtil.getInstance().getSelfNode())){
                TimeUtil.waitForSeconds(1);

                publishBlock();
            }

        }

    }

    private void commitBlock(Message message  ){
        Block deserialized = ConversionUtil.fromJson(message.payload, Block.class);
        TransactionDb.getInstance().insertTransaction(deserialized.getTransactions());
        Block  block = new Block(deserialized.getPreviousHash(), deserialized.getTransactions());
        BlockDb.getInstance().insertBlock(block,false);
        transactions.clear();
//        System.out.println("Produced Block: "+ block);
    }

    public void publishBlock(){
        Block block = new Block(BlockDb.getInstance().getLatestBlockHash(),
                    new ArrayList<>(transactions));
        Block.publish(block);


    }






}
