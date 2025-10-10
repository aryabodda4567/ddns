package org.ddns;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.chain.Node;
import org.ddns.net.Bootstrap;

import java.security.Security;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.

public class Main {
    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        new Bootstrap(args[0]);
        new Node(null);


    }
}