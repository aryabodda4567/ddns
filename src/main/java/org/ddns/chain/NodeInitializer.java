package org.ddns.chain;

import org.ddns.net.*;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;
import org.ddns.util.PersistentStorage;

import java.util.Set;

public class NodeInitializer {
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
        Set<NodeConfig> availableNodesSet = bootstrap.getNodes();

        NodeConfig genesisNode = null;

        for(NodeConfig nodeConfig: availableNodesSet){
            if(nodeConfig.getRole().equals(Role.GENESIS)){
                genesisNode= nodeConfig;
                break;
            }
        }
        if(genesisNode == null){
            ConsolePrinter.printInfo("[NodeInitializer] No Genesis node found");
            ConsolePrinter.printFail("[NodeInitializer] Node SYNC failed");
            return;
        }
        try{
            Message message = new Message(
                    MessageType.SYNC_REQUEST,
                    NetworkUtility.getLocalIpAddress(),
                    PersistentStorage.getPublicKey(),
                    null
            );


            NetworkManager.sendDirectMessage(genesisNode.getIp(), ConversionUtil.toJson(message));
        } catch (Exception e) {
             ConsolePrinter.printFail("[NodeInitializer] Error in sending SYNC_REQUEST to Genesis node");
        }
    }

    private void resolveSyncResponse(){

    }

    private void resolveSyncRequest(){

    }



}
