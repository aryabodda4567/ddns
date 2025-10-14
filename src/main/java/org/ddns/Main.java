package org.ddns;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.chain.Node;
import org.ddns.chain.Role;
import org.ddns.chain.Wallet;
import org.ddns.chain.governance.Nomination;
import org.ddns.net.Bootstrap;
import org.ddns.net.NodeConfig;
import org.ddns.util.NetworkUtility;
import org.ddns.util.PersistentStorage;

import java.security.Security;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.

public class Main {
    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        Bootstrap.initialize(args[0]);

        Bootstrap bootstrap = Bootstrap.getInstance();
        bootstrap.saveOrUpdateNode(new NodeConfig(
                NetworkUtility.getLocalIpAddress(),
                Role.LEADER_NODE,
                Wallet.getKeyPair().getPublic()
        ));

        Node node =new Node(null);
        node.startBootstrapProcess();




    }
}