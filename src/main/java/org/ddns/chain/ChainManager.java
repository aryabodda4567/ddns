package org.ddns.chain;

import org.ddns.util.PersistentStorage;

public class ChainManager {

    public static void setupGenesisNode() {
        PersistentStorage storage = new PersistentStorage();
        PersistentStorage.put(Names.ROLE, Role.GENESIS + "");
    }


}
