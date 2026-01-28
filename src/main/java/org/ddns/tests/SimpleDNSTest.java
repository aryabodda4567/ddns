package org.ddns.tests;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import org.ddns.node.NodesManager;
import org.ddns.util.ConsolePrinter;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import java.security.Security;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class SimpleDNSTest {

    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());

    }
}
