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
import java.util.concurrent.CompletableFuture;

public class Node implements Bootstrap.NodeBootstrapListener {

    private final PublicKey publicKey;
    private final NetworkManager networkManager;
    private final Bootstrap bootstrap;
    private Role role;

    public Node(String privateKey) throws Exception {
        this.role = Role.NONE;

        // Initialize wallet
        if (privateKey == null) {
            KeyPair keyPair = Wallet.getKeyPair();
            this.publicKey = keyPair.getPublic();
            PersistentStorage.put(Names.PRIVATE_KEY, SignatureUtil.getStringFromKey(keyPair.getPrivate()));
        } else {
            this.publicKey = Wallet.getPublicKeyFromPrivateKey(SignatureUtil.getPrivateKeyFromString(privateKey));
            PersistentStorage.put(Names.PRIVATE_KEY, privateKey);
        }
        PersistentStorage.put(Names.PUBLIC_KEY, SignatureUtil.getStringFromKey(publicKey));

        // Single NetworkManager
        this.networkManager = new NetworkManager();

        // Initialize Bootstrap and NodeJoin
        this.bootstrap = new Bootstrap();
        bootstrap.register(networkManager);

        NodeJoin nodeJoin = new NodeJoin();
        nodeJoin.register(networkManager);  // only register, do not start listeners

        // Start network listeners once
        networkManager.startListeners();
    }

    public void startBootstrapProcess() {
        bootstrap.setListener(this);
        bootstrap.createNodesRequest();
    }

    @Override
    public void onBootstrapNodesReceived(Set<NodeConfig> nodes) {
        try {
            NodeJoin nodeJoin = new NodeJoin();
            nodeJoin.register(networkManager); // just register, listeners already running

            NodeCLI nodeCLI = new NodeCLI(this, nodeJoin);
            nodeCLI.start();
//            nodeJoin.createNominationRequest(1);
//
//            TimeUtil.waitForSeconds(2);
//            System.out.println("Total nominations: "+ Nomination.getNominations());
//            TimeUtil.waitForSeconds(2);
//            System.out.println("Votes obtained: " + Nomination.getVotes());
//
//            int votesRequired = 0;
//            for (NodeConfig node : new Bootstrap().getNodes()) {
//                if (!node.getRole().equals(Role.NORMAL_NODE)) votesRequired++;
//            }
//            System.out.println("Votes requires: "+ votesRequired);
//            for (Nomination nomination: Nomination.getNominations()){
//                if(!nomination.isVoted()) nodeJoin.createCastVoteRequest(true,nomination);
//            }
//
//
//            TimeUtil.waitForSeconds(2);
//            System.out.println("Total nominations: "+ Nomination.getNominations());
//            TimeUtil.waitForSeconds(2);
//            System.out.println("Votes obtained: " + Nomination.getVotes());
//             votesRequired = 0;
//            for (NodeConfig node : new Bootstrap().getNodes()) {
//                if (!node.getRole().equals(Role.NORMAL_NODE)) votesRequired++;
//            }
//            System.out.println("Votes requires: "+ votesRequired);
//
//
//            System.out.println(Nomination.getResult());
//            TimeUtil.waitForSeconds(60);
//            System.out.println(Nomination.getResult());

        } catch (Exception e) {
            ConsolePrinter.printFail("[Node] Failed to process NodeJoin: " + e.getMessage());
        }
    }
}
