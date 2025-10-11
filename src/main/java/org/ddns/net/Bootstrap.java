package org.ddns.net;

import org.ddns.chain.Names;
import org.ddns.util.ConversionUtil;
import org.ddns.util.PersistentStorage;

import java.util.HashSet;
import java.util.Set;


public class Bootstrap {
    Set<SystemConfig> nodes;
    Set<SystemConfig> leaders;


    public Bootstrap(String bootstrapNode) {

        PersistentStorage.put(Names.BOOTSTRAP_NODE_IP, bootstrapNode);
        String nodesJson = PersistentStorage.getString(Names.AVAILABLE_NODES);
        if (nodesJson == null) {
            nodes = new HashSet<>();
        } else {
            nodes = ConversionUtil.jsonToSet(nodesJson, SystemConfig.class);
        }

    }

    public Bootstrap() {

        String nodesJson = PersistentStorage.getString(Names.AVAILABLE_NODES);
        if (nodesJson == null) {
            nodes = new HashSet<>();
        } else {
            nodes = ConversionUtil.jsonToSet(nodesJson, SystemConfig.class);
        }

        String leaderNodeJson = PersistentStorage.getString(Names.LEADER_NODES);
        if(leaderNodeJson == null){
            leaders = new HashSet<>();
        }else{
            leaders = ConversionUtil.jsonToSet(leaderNodeJson, SystemConfig.class);
        }
    }

    public String getBootstrapNodeIp() {
        return PersistentStorage.getString(Names.BOOTSTRAP_NODE_IP);
    }

    public void addNode(SystemConfig systemConfig) {
        nodes.add(systemConfig);
        save();
    }

    public void addLeaderNode(SystemConfig systemConfig) {
        leaders.add(systemConfig);
        save();
    }
    public Set<SystemConfig> getLeaders(){return leaders;}

    public void addNodes(Set<SystemConfig> systemConfig) {
        nodes.addAll(systemConfig);
        save();
    }

    private void save() {
        PersistentStorage.put(Names.AVAILABLE_NODES, ConversionUtil.toJson(nodes));
    }

    public Set<SystemConfig> getNodes() {
        return nodes;
    }

    public void deleteNode(SystemConfig systemConfig) {
        nodes.remove(systemConfig);
        save();
    }

    public void updateNode(SystemConfig oldNode, SystemConfig newNode) {
        nodes.remove(oldNode);
        nodes.add(newNode);
        save();
    }

}
