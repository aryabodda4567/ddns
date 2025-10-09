package org.ddns.chain;

import org.ddns.bc.SignatureUtil;
import org.ddns.bc.Transaction;
import org.ddns.bc.TransactionType;
import org.ddns.net.Message;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
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
    public static void discoveryRequest(Message message ,PublicKey publicKey) {
        String senderIp = message.senderIp;

        Map<String, String> map = new HashMap<>();
        PersistentStorage storage = new PersistentStorage();

        map.put(Names.TOTAL_NODE_COUNT, storage.getInt(Names.TOTAL_NODE_COUNT) + "");
        map.put(Names.TOTAL_LEADER_COUNT,new PersistentStorage().getInt(Names.TOTAL_LEADER_COUNT)+"");

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
        NetworkManager.broadcast(ConversionUtil.toJson(message));
    }

    /**
     * Returns the role required to process the given message type.
     */
    public static Role requiredRole(Message message) {
        if (message.type.equals(MessageType.DISCOVERY_REQUEST)
                || message.type.equals(MessageType.JOIN_REQUEST_TX)
                || message.type.equals(MessageType.SYNC_REQUEST)
                || message.type.equals(MessageType.PROMOTION_REQUEST_TX)) {
            return Role.LEADER_NODE;
        }
        return Role.ANY;
    }

    /**
     * Initializes node configurations from payload received in DISCOVERY_ACK.
     */
    public static void initializeConfigs(Map<String, String> payload) {
        PersistentStorage storage = new PersistentStorage();

        storage.put(Names.TOTAL_NODE_COUNT,
                Integer.parseInt(payload.get(Names.TOTAL_NODE_COUNT)));

        storage.put(Names.TOTAL_LEADER_COUNT,
                Integer.parseInt(payload.get(Names.TOTAL_LEADER_COUNT)));
    }
}
