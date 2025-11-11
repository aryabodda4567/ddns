package org.ddns;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.bootstrap.BootstrapNode;
import org.ddns.chain.Wallet;
import org.ddns.constants.Role;
import org.ddns.db.*;
import org.ddns.governance.Election;
import org.ddns.net.NetworkManager;
import org.ddns.node.NodeConfig;
import org.ddns.node.NodesManager;
import org.ddns.util.NetworkUtility;

import java.security.KeyPair;
import java.security.Security;


public class Main {
    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
//
//        // Clean slate
//        DBUtil.getInstance().clearAllStorage();
//        BootstrapDB.getInstance().clearNodes();
//        DBUtil.getInstance().deleteDatabaseFile();
//        BootstrapDB.getInstance().clearNodes();

        DNSDb.getInstance().truncateDatabase(false);
        TransactionDb.getInstance().truncateDatabase(false);
        BlockDb.getInstance().truncateDatabase(false);


        System.out.println(DBUtil.getInstance().getAllNodes().size());

        System.out.println(BootstrapDB.getInstance().getAllNodes().size());

        DBUtil.getInstance().saveBootstrapIp(NetworkUtility.getLocalIpAddress());

        KeyPair kp = Wallet.getKeyPair();
        DBUtil.getInstance().setSelfNode(new NodeConfig(NetworkUtility.getLocalIpAddress(),
                Role.GENESIS, kp.getPublic()));
        DBUtil.getInstance().saveKeys(kp.getPublic(), kp.getPrivate());

        // Create network manager
        NetworkManager networkManager = new NetworkManager();

        // Bind BootstrapNode to NetworkManager (registers as handler)
        BootstrapNode bootstrapNode = new BootstrapNode(networkManager);

        Election election = new Election(networkManager);

        NodesManager nodesManager = new NodesManager(networkManager, election);

        networkManager.startListeners();

//        NetworkManager.sendFile(NetworkUtility.getLocalIpAddress(), DNSDb.getInstance().getDatabaseFilePath());


//
//        nodesManager.createFetchRequest();
//
//        TimeUtil.waitForSeconds(1);

//        for (Nomination nomination: election.getNominations()){
//          //  System.out.println(nomination);
//            TimeUtil.waitForSeconds(1);
//            election.casteVote(nomination);
//        }
//        TimeUtil.waitForSeconds(2);
//
//        System.out.println(election.getResult());

        System.out.println("BootstrapNode bound to NetworkManager. Listeners running.");
//         Your app can now send/receive messages and BootstrapNode will handle them.
    }
}
