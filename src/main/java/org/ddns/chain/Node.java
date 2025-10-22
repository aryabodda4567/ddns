package org.ddns.chain;

import org.ddns.NodeCLI;
import org.ddns.bc.SignatureUtil;
import org.ddns.chain.governance.NodeJoin;
import org.ddns.chain.governance.Nomination;
import org.ddns.net.*;
import org.ddns.util.*;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Set;

/**
 * Represents a blockchain node responsible for initialization,
 * networking, and participating in governance processes.
 */
public class Node implements Bootstrap.NodeBootstrapListener, NodeJoin.NodeJoinListener {

    private final PublicKey publicKey;
    private final NetworkManager networkManager;
    private final Bootstrap bootstrap;
    private Role role;

    public Node(String privateKey) throws Exception {
        this.role = Role.NONE;

        // --- Wallet Initialization ---
        if (privateKey == null) {
            KeyPair keyPair = Wallet.getKeyPair();
            this.publicKey = keyPair.getPublic();
            PersistentStorage.put(Names.PRIVATE_KEY, SignatureUtil.getStringFromKey(keyPair.getPrivate()));
        } else {
            this.publicKey = Wallet.getPublicKeyFromPrivateKey(SignatureUtil.getPrivateKeyFromString(privateKey));
            PersistentStorage.put(Names.PRIVATE_KEY, privateKey);
        }
        PersistentStorage.put(Names.PUBLIC_KEY, SignatureUtil.getStringFromKey(publicKey));

        // --- Initialize Core Components ---
        this.networkManager = new NetworkManager();
        this.bootstrap = Bootstrap.getInstance();
        bootstrap.register(networkManager);

        // Register governance system
        NodeJoin nodeJoin = new NodeJoin();
        nodeJoin.register(networkManager);
        nodeJoin.setListener(this); // ✅ Register Node as listener

        // Start listening for messages
        networkManager.startListeners();
    }

    /**
     * Begins the bootstrap discovery process to find and connect to other nodes.
     */
    public void startBootstrapProcess() {
        bootstrap.setListener(this);
        bootstrap.createNodesRequest();
    }

    /**
     * Called when bootstrap nodes are received successfully.
     */
    @Override
    public void onBootstrapNodesReceived(Set<NodeConfig> nodes) {
        try {
            NodeJoin nodeJoin = new NodeJoin();
            nodeJoin.register(networkManager);
            nodeJoin.setListener(this); // ✅ Register again for event callbacks

            NodeCLI nodeCLI = new NodeCLI(this, nodeJoin );
            nodeCLI.start();

            nodeJoin.createNominationRequest(1);

        } catch (Exception e) {
            ConsolePrinter.printFail("[Node] Failed to process NodeJoin: " + e.getMessage());
        }
    }

    // --- NodeJoinListener Events ---

    @Override
    public void onNodeJoinWin() {
        ConsolePrinter.printSuccess("[Node] 🎉 Node has been approved and will be added to the network!");
        this.role = Role.NORMAL_NODE;
        PersistentStorage.put(Names.ROLE, this.role.name());
    }

    @Override
    public void onNodeJoinLose() {
        ConsolePrinter.printFail("[Node] ❌ Node's join request was rejected.");
        this.role = Role.NONE;
        PersistentStorage.put(Names.ROLE, this.role.name());
        ConsolePrinter.printInfo("[Node] System shutdown");
        this.networkManager.stop();
        System.exit(0);
    }

    @Override
    public void onNodeJoinProgress() {
        ConsolePrinter.printInfo("[Node] 🕓 Voting still in progress, waiting for more votes...");
    }
}
