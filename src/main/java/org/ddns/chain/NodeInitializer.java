package org.ddns.chain;

import org.ddns.net.*;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.PersistentStorage;


public class NodeInitializer{
    private final Bootstrap bootstrap ;

    public NodeInitializer(){
        this.bootstrap = Bootstrap.getInstance();
    }

    public void initGenesisNode(){
        ConsolePrinter.printInfo("[NodeInitializer] Initializing as Genesis Node ");
        PersistentStorage.put(Names.ROLE,Role.GENESIS.name());
        bootstrap.createAddNewNodeRequest();
    }
    public  void initNormalNode(){
        ConsolePrinter.printInfo("[NodeInitializer] Initializing as Normal Node ");
        PersistentStorage.put(Names.ROLE,Role.NORMAL_NODE.name());
        bootstrap.createAddNewNodeRequest();
    }
    public void initPromoteNode(){
        ConsolePrinter.printInfo("[NodeInitializer] Promoted as Leader Node ");
        PersistentStorage.put(Names.ROLE,Role.LEADER_NODE.name());
        bootstrap.createPromoteNodeRequest();
    }

    public void sync(){
    }

    private void resolveSyncResponse(){

    }

    private void resolveSyncRequest(){

    }
}
