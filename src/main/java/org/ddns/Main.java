package org.ddns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.bc.SignatureUtil;
import org.ddns.bootstrap.BootstrapNode;
import org.ddns.chain.Wallet;
import org.ddns.consensus.ConsensusEngine;
import org.ddns.db.*;
import org.ddns.governance.Election;
import org.ddns.net.NetworkManager;
import org.ddns.node.NodesManager;
import org.ddns.web.WebServer;

import java.security.KeyPair;
import java.security.Security;


/**
 * Main application entrypoint for the dDNS node app.
 * <p>
 * Features:
 * - Node/bootstrap selection and configuration
 * - Election create / view result / cast vote flow (with hashed password)
 * - NodesManager integration for fetch/add/promote/sync
 * - DNS CLI sub-menu (configure DNS client, create/lookup/reverse/send management commands)
 * <p>
 * Notes:
 * - Sensitive inputs (private key, election password) are read from Console when available;
 * fall back to visible Scanner input in IDEs.
 * - Election password is hashed (SHA-256) before being stored in DBUtil.
 */
public class Main {

    public static final String ELECTION_PASSWORD = "election_password"; // DB key
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private NetworkManager networkManager;
    private Election election;

    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        log.info("Bootstrap nodes: " + BootstrapDB.getInstance().getAllNodes());
        log.info("Local node: " + DBUtil.getInstance().getSelfNode());
        log.info("Known nodes: " + DBUtil.getInstance().getAllNodes());

        Main app = new Main();
        app.init();
        log.info("BootstrapNode bound to NetworkManager. Listeners running.");


    }

    public void init() throws Exception {
        // one Scanner for lifecycle


        networkManager = new NetworkManager();
        new BootstrapNode(networkManager);
        election = new Election(networkManager);
        new NodesManager(networkManager, election);
        networkManager.registerHandler(new ConsensusEngine());
        networkManager.startListeners();
        WebServer.start();
    }


}
