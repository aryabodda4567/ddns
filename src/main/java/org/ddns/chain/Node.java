package org.ddns.chain;

import org.ddns.NodeCLI;
import org.ddns.bc.SignatureUtil; // Still needed for key string conversion
import org.ddns.chain.governance.NodeJoin;
import org.ddns.db.DBUtil; // Import the DBUtil class
import org.ddns.net.*;
import org.ddns.util.*;

import java.security.KeyPair;
import java.security.PrivateKey; // Import PrivateKey
import java.security.PublicKey;
import java.util.Set;

/**
 * Represents a blockchain node responsible for initialization,
 * networking, and participating in governance processes.
 * Uses DBUtil for persistence.
 */
public class Node implements Bootstrap.NodeBootstrapListener, NodeJoin.NodeJoinListener {

    private final NetworkManager networkManager;
    private final Bootstrap bootstrap;
    private final DBUtil dbUtil; // Instance of the database utility
    private Role role;

    public Node(String privateKeyStrInput) throws Exception {
        this.role = Role.NONE;
        this.dbUtil = DBUtil.getInstance(); // Get the singleton DB instance

        PrivateKey privateKey ;
        PublicKey publicKey;
        if(privateKeyStrInput == null){
            KeyPair keyPair = Wallet.getKeyPair();
            privateKey = keyPair.getPrivate();
            publicKey = keyPair.getPublic();
        }else{
            privateKey = SignatureUtil.getPrivateKeyFromString(privateKeyStrInput);
            publicKey = Wallet.getPublicKeyFromPrivateKey(privateKey);
        }
        // save keys
        dbUtil.saveKeys(publicKey,privateKey);




        // --- Initialize Core Components ---
        this.networkManager = new NetworkManager();
        this.bootstrap = Bootstrap.getInstance(); // Use singleton
        bootstrap.register(networkManager);

        // Register governance system
        NodeJoin nodeJoin = new NodeJoin();
        nodeJoin.register(networkManager);
        nodeJoin.setListener(this);

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
     * Initiates the NodeCLI and the nomination request process.
     */
    @Override
    public void onBootstrapNodesReceived(Set<NodeConfig> nodes) {
        try {


            Set<NodeConfig> nodeConfigSet = dbUtil.getAllNodes();
            if(nodeConfigSet == null || nodeConfigSet.isEmpty()){
                ConsolePrinter.printInfo("No nodes found");
                NodeInitializer nodeInitializer = new NodeInitializer();
                nodeInitializer.initGenesisNode();
            }else{
                NodeJoin nodeJoin = new NodeJoin();

                nodeJoin.register(networkManager);

                nodeJoin.setListener(this);

                NodeCLI nodeCLI = new NodeCLI(this, nodeJoin ); // Pass `this` Node instance

//              Start the CLI in a new thread so it doesn't block network/consensus
                new Thread(nodeCLI::start).start();

                ConsolePrinter.printInfo("Node(s) found "+nodeConfigSet.size());

                nodeJoin.createNominationRequest(1);
            }

        } catch (Exception e) {
            ConsolePrinter.printFail("[Node] Failed to process bootstrap response or start CLI: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for debugging
        }
    }

    // --- NodeJoinListener Events ---

    @Override
    public void onNodeJoinWin() {
        ConsolePrinter.printSuccess("[Node] 🎉 Node has been approved and will be added to the network!");
        this.role = Role.NORMAL_NODE;
        dbUtil.saveRole(this.role); // Save updated role to DB
        NodeInitializer nodeInitializer = new NodeInitializer();
        nodeInitializer.initNormalNode();

    }

    @Override
    public void onNodeJoinLose() {
        ConsolePrinter.printFail("[Node] ❌ Node's join request was rejected.");
        this.role = Role.NONE;
        dbUtil.saveRole(this.role); // Save updated role to DB
        ConsolePrinter.printInfo("[Node] System shutting down due to join rejection.");
        this.networkManager.stop();
        System.exit(0);
    }

    @Override
    public void onNodeJoinProgress() {
        ConsolePrinter.printInfo("[Node] 🕓 Voting still in progress, waiting for more votes...");
    }

    // --- Getter for CLI ---
    public PublicKey getPublicKey() {
        try{
            return dbUtil.getPublicKey();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public Role getRole() {
        return role;
    }
}