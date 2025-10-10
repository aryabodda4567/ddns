package org.ddns.net;

import org.ddns.chain.Names;
import org.ddns.util.ConversionUtil;
import org.ddns.util.PersistentStorage;

import java.util.HashSet;
import java.util.Set;


public class Bootstrap {
    Set<SystemConfig> nodes;
    PersistentStorage storage;

    public Bootstrap() {
        storage = new PersistentStorage();
        String nodesJson = storage.getString(Names.AVAILABLE_NODES);
        if (nodesJson == null) {
            nodes = new HashSet<>();
        } else {
            nodes = ConversionUtil.jsonToSet(nodesJson, SystemConfig.class);
        }
    }

    public void addNode(SystemConfig systemConfig) {
        if (nodes.contains(systemConfig)) return;
        nodes.add(systemConfig);
        save();
    }

    private void save() {
        storage.put(Names.AVAILABLE_NODES, ConversionUtil.toJson(nodes));
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
