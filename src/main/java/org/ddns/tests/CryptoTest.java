package org.ddns.tests;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.chain.Wallet;
import org.ddns.constants.Role;
import org.ddns.db.DBUtil;
import org.ddns.node.NodeConfig;
import org.ddns.util.TimeUtil;

import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Security;
import java.util.HashSet;
import java.util.Set;

public class CryptoTest {
    public static void main(String[] args) {
        Security.addProvider(new BouncyCastleProvider());

        Set<NodeConfig> nodeConfigs = new HashSet<>();

        for (int i = 0; i < 10; i++) {
            nodeConfigs.add(new NodeConfig("102.30.20.10", Role.NONE, Wallet.getKeyPair().getPublic()));
        }

        KeyPair keyPair = Wallet.getKeyPair();
        nodeConfigs.add(new NodeConfig("102.30.20.10", Role.NONE, keyPair.getPublic()));

        System.out.println(nodeConfigs.contains(new NodeConfig("102.30.20.10", Role.NONE, Wallet.getKeyPair().getPublic())));


    }
}
