package org.ddns.chain;

import org.ddns.bc.SignatureUtil;
import org.ddns.bc.Transaction;
import org.ddns.net.*;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;
import org.ddns.util.PersistentStorage;

import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles incoming network messages and triggers appropriate actions.
 */
public class MessageHandler {

    /**
     * Responds to a DISCOVERY_REQUEST by sending back a DISCOVERY_ACK
     * containing current network statistics (node/leader counts).
     */
    public static void discoveryRequest(Message message, PublicKey publicKey) {
        String senderIp = message.senderIp;

        Map<String, String> map = new HashMap<>();


        map.put(Names.TOTAL_NODE_COUNT, PersistentStorage.getInt(Names.TOTAL_NODE_COUNT) + "");
        map.put(Names.TOTAL_LEADER_COUNT, PersistentStorage.getInt(Names.TOTAL_LEADER_COUNT) + "");

        Message payloadMessage = new Message(
                MessageType.DISCOVERY_ACK,
                NetworkUtility.getLocalIpAddress(),
                publicKey,
                ConversionUtil.toJson(map)
        );

        NetworkManager.sendDirectMessage(senderIp, ConversionUtil.toJson(payloadMessage));
    }

    /**
     * Creates a JOIN_REQUEST transaction for the given node and multicasts it.
     */
    public static void createJoinRequest(PublicKey senderPublicKey) throws Exception {


        Message message = new Message(
                MessageType.JOIN_REQUEST_TX,
                NetworkUtility.getLocalIpAddress(),
                senderPublicKey,
                null
        );
        System.out.println("Sending broadcast");
        NetworkManager.sendToNodes(ConversionUtil.toJson(message),Role.LEADER_NODE);
        NetworkManager.sendToNodes(ConversionUtil.toJson(message),Role.GENESIS);
    }



    /**
     * Initializes node configurations from payload received in DISCOVERY_ACK.
     */
    public static void initializeConfigs(Map<String, String> payload) {


        PersistentStorage.put(Names.TOTAL_NODE_COUNT,
                Integer.parseInt(payload.get(Names.TOTAL_NODE_COUNT)));

        PersistentStorage.put(Names.TOTAL_LEADER_COUNT,
                Integer.parseInt(payload.get(Names.TOTAL_LEADER_COUNT)));
    }

    public static void resolveBootstrapRequest(Message message) throws Exception {
        String receiverIp = message.senderIp;
        Bootstrap bootstrap = new Bootstrap();
        String resultJson = ConversionUtil.toJson(bootstrap.getNodes());
        Map<String, String> map = new HashMap<>();
        map.put("result", resultJson);
        Message resultMessage = new Message(
                MessageType.BOOTSTRAP_RESPONSE,
                NetworkUtility.getLocalIpAddress(),
                SignatureUtil.getPublicKeyFromString(message.senderPublicKey),
                ConversionUtil.toJson(map)
        );
        System.out.println("Resolving bootstrap request");
        NetworkManager.sendDirectMessage(receiverIp, ConversionUtil.toJson(resultMessage));
    }

    public static void resolveBootstrapResponse(Map<String, String> map) {
        Bootstrap bootstrap = new Bootstrap();
        System.out.println("Resolving bootstrap response");
        bootstrap.addNodes(ConversionUtil.jsonToSet(map.get("result"), SystemConfig.class));

    }

    public static void createBootstrapRequest(PublicKey publicKey) {
        Bootstrap bootstrap = new Bootstrap();
        String bootstrapNodeIp = bootstrap.getBootstrapNodeIp();
        Message message = new Message(
                MessageType.BOOTSTRAP_REQUEST,
                NetworkUtility.getLocalIpAddress(),
                publicKey,
                null
        );
        System.out.println("Sending bootstrap request.");
        NetworkManager.sendDirectMessage(bootstrapNodeIp, ConversionUtil.toJson(message));
    }

    public static void broadcastTransaction(Transaction transaction){
        PublicKey publicKey = null;
        try{
            publicKey = SignatureUtil.getPublicKeyFromString(PersistentStorage.getString(Names.PUBLIC_KEY));
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

        if(publicKey == null){
            System.out.println("No public key found");
            return;
        }

        Map<String,String> payloadMap = new HashMap<>();
        payloadMap.put("TRANSACTION",ConversionUtil.toJson(transaction));
        Message message =new Message(
                MessageType.TRANSACTION,
                NetworkUtility.getLocalIpAddress(),
                publicKey,
                ConversionUtil.toJson(payloadMap)


        );

        NetworkManager.sendToNodes(ConversionUtil.toJson(message), Role.LEADER_NODE);
        NetworkManager.sendToNodes(ConversionUtil.toJson(message),Role.GENESIS);
    }

    public static void addLeaderRequest() throws Exception {
        String ip = NetworkUtility.getLocalIpAddress();
        PublicKey publicKey =  SignatureUtil.getPublicKeyFromString(PersistentStorage.getString(Names.PUBLIC_KEY));
        Role role = ConversionUtil.fromJson(PersistentStorage.getString(Names.ROLE), Role.class);
        SystemConfig systemConfig = new SystemConfig(ip,role,publicKey);
        Map<String , String> map = new HashMap<>();
        map.put("NODE",ConversionUtil.toJson(systemConfig));
        Message message = new Message(
                MessageType.ADD_LEADER,
                NetworkUtility.getLocalIpAddress(),
                publicKey,
                ConversionUtil.toJson(map)

        );
        NetworkManager.broadcast(ConversionUtil.toJson(message));
    }
    public static void addLeaderResolve(Map<String,String> payLoad){
        SystemConfig systemConfig = ConversionUtil.fromJson(payLoad.get("NODE"), SystemConfig.class);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.addLeaderNode(systemConfig);
    }

}
