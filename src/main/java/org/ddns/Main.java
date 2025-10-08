package org.ddns;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
/// PORT 6969 for all communications
///
public class Main {
    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        if (args.length < 1) {
            System.out.println("Usage: java Node <port> [initial-peer-host:port]");
            return;
        }
        int port = Integer.parseInt(args[0]);
        String initialPeer = args.length > 1 ? args[1] : null;

    }
}