package org.ddns.chain;

import org.ddns.util.PersistentStorage;

public class ChainManager {

    public  static  void setupGenesisNode(){
        PersistentStorage storage = new PersistentStorage();
        storage.put(Names.ROLE, Role.GENESIS+"");
    }






}
