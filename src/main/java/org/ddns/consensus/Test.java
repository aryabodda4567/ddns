package org.ddns.consensus;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.chain.Wallet;
import org.ddns.constants.Role;
import org.ddns.node.NodeConfig;
import org.ddns.util.TimeUtil;

import java.security.Security;
import java.util.Objects;

public class Test {

    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        Test t = new Test();
        t.consensusFailoverTest();


    }


    public void CircularQueueTest(){
        CircularQueue circularQueue = CircularQueue.getInstance();

        NodeConfig node1 = new NodeConfig("192.168.0.1", Role.GENESIS, Wallet.getKeyPair().getPublic());
        NodeConfig node2 = new NodeConfig("192.168.0.2", Role.LEADER_NODE, Wallet.getKeyPair().getPublic());
        NodeConfig node3 = new NodeConfig("192.168.0.3", Role.NORMAL_NODE, Wallet.getKeyPair().getPublic());
        NodeConfig node4 = new NodeConfig("192.168.0.4", Role.NORMAL_NODE, Wallet.getKeyPair().getPublic());


        for(int i =0; i< 14 ;i++){

            TimeUtil.waitForSeconds(3);
            if(i ==0){
                circularQueue.addNode(new QueueNode(node1,1));
                System.out.println("Node 1 added");
            }

            if(i == 3){
                circularQueue.addNode(new QueueNode(node2,2));
                System.out.println("Node 2 added");
            }

            if(i ==6){
                circularQueue.addNode(new QueueNode(node3,3));
                System.out.println("Node 3 added");
            }

            if(i == 9){
                circularQueue.addNode(new QueueNode(node4,4));
                System.out.println("Node 4 added");
            }


            System.out.println("Before circle: "+ Objects.requireNonNull(circularQueue.peek()).sno);
            circularQueue.rotate();
            System.out.println("After circle: "+ Objects.requireNonNull(circularQueue.peek()).sno);

        }
    }


    public void consensusFailoverTest() throws Exception {

        System.out.println("=== CONSENSUS FAILOVER TEST START ===");

        CircularQueue queue = CircularQueue.getInstance();

        NodeConfig node1 = new NodeConfig("192.168.0.1", Role.GENESIS, Wallet.getKeyPair().getPublic());
        NodeConfig node2 = new NodeConfig("192.168.0.2", Role.LEADER_NODE, Wallet.getKeyPair().getPublic());
        NodeConfig node3 = new NodeConfig("192.168.0.3", Role.NORMAL_NODE, Wallet.getKeyPair().getPublic());

        queue.addNode(new QueueNode(node1, 1));
        queue.addNode(new QueueNode(node2, 2));
        queue.addNode(new QueueNode(node3, 3));

        System.out.println("Initial Leader: " + queue.peek().nodeConfig.getIp());

        // Start consensus scheduler
        ConsensusSystem.start();

        // We simulate: Node1 NEVER produces a block

        // Wait longer than BLOCK_BUFFER_TIME (15s in your engine)
        Thread.sleep(20000);

        System.out.println("Leader after timeout: " + queue.peek().nodeConfig.getIp());

        // Wait again to see next rotation
        Thread.sleep(20000);

        System.out.println("Leader after second timeout: " + queue.peek().nodeConfig.getIp());

        System.out.println("=== TEST END ===");
    }


}
