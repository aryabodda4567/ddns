package org.ddns.crypto;

import org.ddns.bc.SignatureUtil;
import org.ddns.bootstrap.BootstrapNode;
import org.ddns.constants.Role;
import org.ddns.db.BootstrapDB;
import org.ddns.db.DBUtil;
import org.ddns.net.Message;
import org.ddns.node.NodeConfig;
import org.ddns.util.ConversionUtil;
import org.w3c.dom.Node;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CryptManager handles secure message exchange between nodes.
 *
 * Security model:
 * 1. Message is encrypted using ECDH derived AES key.
 * 2. Message is signed using sender private key.
 * 3. Receiver decrypts using ECDH shared secret.
 * 4. Signature verification ensures authenticity.
 * 5. Node existence verification prevents unauthorized nodes.
 *
 * This ensures:
 * Confidentiality  -> AES encryption
 * Authenticity     -> Digital signature
 * Integrity        -> Signature verification
 * Network trust    -> Node membership validation
 */
public class CryptManager {

    private static final Logger LOGGER = Logger.getLogger(CryptManager.class.getName());

    /**
     * Encrypts a plaintext message for a receiver.
     *
     * Steps:
     * 1. Derive shared AES key using ECDH
     * 2. Encrypt plaintext
     * 3. Sign plaintext
     * 4. Package encrypted payload + signature + sender public key
     *
     * @param receiverPublicKey receiver's EC public key
     * @param plaintext message to encrypt
     * @return MessageWrapper containing encrypted payload
     */
    public static String encrypt(PublicKey receiverPublicKey, String plaintext) throws Exception {

        if (receiverPublicKey == null) {
            throw new IllegalArgumentException("Receiver public key cannot be null");
        }

        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("Plaintext cannot be empty");
        }

        DBUtil db = DBUtil.getInstance();

        PrivateKey senderPrivateKey = db.getPrivateKey();
        PublicKey senderPublicKey = db.getPublicKey();

        if (senderPrivateKey == null || senderPublicKey == null) {
            throw new IllegalStateException("Local node keys are not initialized");
        }

        try {

            // Encrypt message using ECDH derived AES key
            String encryptedMessage = Crypto.encryptForReceiver(
                    plaintext,
                    senderPrivateKey,
                    receiverPublicKey
            );

            // Create digital signature of plaintext
            byte[] signature = SignatureUtil.sign(senderPrivateKey, plaintext);

            MessageWrapper messageWrapper =new MessageWrapper(
                    senderPublicKey,
                    signature,
                    encryptedMessage
            );
            return  ConversionUtil.toJson(messageWrapper);

        } catch (Exception e) {

            LOGGER.log(Level.SEVERE, "Encryption failed", e);
            throw e;

        }
    }

    /**
     * Decrypt incoming message and verify authenticity.
     *
     * Validation sequence:
     * 1. Decrypt ciphertext
     * 2. Verify digital signature
     * 3. Validate sender node exists in network
     *
     * @param jsonString encrypted message
     * @return decrypted plaintext
     */
    public static String decrypt(String jsonString) throws Exception {

        MessageWrapper messageWrapper = ConversionUtil.fromJson(jsonString, MessageWrapper.class);

        if (messageWrapper == null) {
            throw new IllegalArgumentException("MessageWrapper cannot be null");
        }

        DBUtil db = DBUtil.getInstance();

        PrivateKey receiverPrivateKey = db.getPrivateKey();

        if (receiverPrivateKey == null) {
            throw new IllegalStateException("Receiver private key is not initialized");
        }

        try {

            // Step 1 — Decrypt message
            String plainText = Crypto.decryptFromSender(
                    messageWrapper.encryptedMessage,
                    receiverPrivateKey,
                    messageWrapper.senderPublicKey
            );

            // Step 2 — Verify signature
            boolean validSignature = SignatureUtil.verify(
                    messageWrapper.senderPublicKey,
                    messageWrapper.signature,
                    plainText
            );

            if (!validSignature) {
                throw new SecurityException("Signature verification failed");
            }

            Message message = ConversionUtil.fromJson(plainText, Message.class);

//            Exclude this from checking
            if(message.isExclude() ) return  plainText;

            // Step 3 — Verify sender node exists in network
            NodeConfig nodeConfig = constructNodeConfig(message);

            if (!isNodeExist(nodeConfig)) {
                throw new SecurityException("Unknown node attempted communication");
            }

            return plainText;

        } catch (Exception e) {

            LOGGER.log(Level.WARNING, "Message decryption or validation failed", e);
            throw e;

        }
    }

    /**
     * Constructs NodeConfig from message payload.
     * Used to verify if sender node exists in network topology.
     */
    private static NodeConfig constructNodeConfig(Message message) throws Exception {


        if (message == null) {
            throw new IllegalArgumentException("Invalid message format");
        }

        // Exclude this message from checking
        if(message.isExclude()) return null;

        PublicKey senderPublicKey = SignatureUtil.getPublicKeyFromString(message.senderPublicKey);

        String ip = message.senderIp;

        Role role = isBootstrapIp(ip) ? Role.BOOTSTRAP : Role.NONE;

        return new NodeConfig(ip, role, senderPublicKey);
    }

    /**
     * Checks if given IP belongs to bootstrap node.
     */
    private static boolean isBootstrapIp(String ip) {

        NodeConfig bootstrap = DBUtil.getInstance().getBootstrapNode();

        return bootstrap != null && bootstrap.getIp().equals(ip);
    }

    /**
     * Validates if node exists in the distributed network.
     *
     * Combines:
     * - Local node registry
     * - Bootstrap node registry
     * 
     * Special case: Bootstrap node is always trusted
     */
    private static boolean isNodeExist(NodeConfig nodeConfig) {

        if (nodeConfig == null) {
            return false;
        }

        // Check if this is the bootstrap node - always trust it
        NodeConfig bootstrapNode = DBUtil.getInstance().getBootstrapNode();
        if (bootstrapNode != null && 
            bootstrapNode.getIp().equals(nodeConfig.getIp()) && 
            bootstrapNode.getPublicKey().equals(nodeConfig.getPublicKey())) {
            return true;
        }

        Set<NodeConfig> localNodes = DBUtil.getInstance().getAllNodes();
        Set<NodeConfig> bootstrapNodes = BootstrapDB.getInstance().getAllNodes();
        NodeConfig selfNode = DBUtil.getInstance().getSelfNode();
        Set<NodeConfig> union = new HashSet<>();

        if (localNodes != null) {
            union.addAll(localNodes);
        }
        if (bootstrapNodes != null) {
            union.addAll(bootstrapNodes);
        }
        if (selfNode != null) union.add(selfNode);

        for (NodeConfig n : union) {
            if (n != null && n.getIp().equals(nodeConfig.getIp()) && n.getPublicKey().equals(nodeConfig.getPublicKey())) {
                return true;
            }
        }

        return false;
    }

}