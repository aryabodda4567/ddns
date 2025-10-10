package org.ddns;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.chain.Node;
import org.ddns.chain.Role;
import org.ddns.net.Bootstrap;
import org.ddns.net.SystemConfig;

import java.security.Security;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.

public class Main {
    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        Bootstrap bootstrap = new Bootstrap();
        SystemConfig systemConfig = new SystemConfig(args[0], Role.BOOTSTRAP);
        bootstrap.addNode(systemConfig);

        new Node(null);


    }
}