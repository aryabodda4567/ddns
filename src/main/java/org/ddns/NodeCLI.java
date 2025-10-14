package org.ddns;

import org.ddns.chain.*;
import org.ddns.chain.governance.Nomination;
import org.ddns.chain.governance.NodeJoin;
import org.ddns.net.Bootstrap;
import org.ddns.net.NodeConfig;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.TimeUtil;

import java.util.List;
import java.util.Set;

public class NodeCLI {

    private final Node node;
    private final NodeJoin nodeJoin;
    private final Bootstrap bootstrap;

    public NodeCLI(Node node, NodeJoin nodeJoin) {
        this.node = node;
        this.bootstrap = Bootstrap.getInstance();
        this.nodeJoin = nodeJoin;
    }

    public void start() {
        var scanner = new java.util.Scanner(System.in);

        while (true) {
            printMenu();
            String choice = scanner.nextLine().trim();

            switch (choice) {
                case "1" -> startBootstrap();
                case "2" -> createElection();
                case "3" -> showNominations();
                case "4" -> castVotes();
                case "5" -> showVoteCount();
                case "6" -> showElectionResult();
                case "7" -> showNodeStatus();
                case "0" -> {
                    ConsolePrinter.printInfo("Exiting CLI...");
                    return;
                }
                default -> ConsolePrinter.printWarning("Invalid option. Try again.");
            }
        }
    }

    private void printMenu() {
        System.out.println("\n===== dDNS Node CLI =====");
        System.out.println("1. Start Bootstrap Process");
        System.out.println("2. Create Election / Nomination");
        System.out.println("3. Show Current Nominations");
        System.out.println("4. Cast Votes for Pending Nominations");
        System.out.println("5. Show Vote Count");
        System.out.println("6. Show Election Result");
        System.out.println("7. Show Node Status");
        System.out.println("0. Exit");
        System.out.print("Choose an option: ");
    }

    private void startBootstrap() {
        ConsolePrinter.printInfo("Starting bootstrap process...");
        node.startBootstrapProcess();
    }

    private void createElection() {
        ConsolePrinter.printInfo("Creating new nomination/election...");
        boolean success = nodeJoin.createNominationRequest(1); // 1-minute election for demo
        if (success) {
            ConsolePrinter.printSuccess("Nomination created and broadcasted successfully.");
        } else {
            ConsolePrinter.printFail("Failed to create nomination.");
        }
    }

    private void showNominations() {
        List<Nomination> nominations = Nomination.getNominations();
        if (nominations.isEmpty()) {
            ConsolePrinter.printInfo("No nominations available.");
            return;
        }

        System.out.println("\nCurrent Nominations:");
        for (Nomination n : nominations) {
            System.out.println(" - IP: " + n.getIpAddress()
                    + ", Type: " + n.getNominationType()
                    + ", Voted: " + n.isVoted());
        }
    }

    private void castVotes() {
        List<Nomination> nominations = Nomination.getNominations();
        if (nominations.isEmpty()) {
            ConsolePrinter.printInfo("No nominations to vote for.");
            return;
        }

        for (Nomination nomination : nominations) {
            if (!nomination.isVoted()) {
                nodeJoin.createCastVoteRequest(true, nomination);
                ConsolePrinter.printSuccess("Vote cast for: " + nomination.getIpAddress());
            }
        }
    }

    private void showVoteCount() {
        int votes = Nomination.getVotes();
        System.out.println("Votes obtained: " + votes);

        int votesRequired = 0;
        for (NodeConfig nodeConfig :  Bootstrap.getInstance().getNodes()) {
            if (!nodeConfig.getRole().equals(Role.NORMAL_NODE)) votesRequired++;
        }
        System.out.println("Votes required: " + votesRequired);
    }

    private void showElectionResult() {
        int result = Nomination.getResult();
        switch (result) {
            case 0 -> {
                ConsolePrinter.printSuccess("Nomination approved (All votes received).");
                NodeInitializer nodeInitializer = new NodeInitializer();
                nodeInitializer.initNormalNode();
                nodeInitializer.initPromoteNode();

            }
            case 2 -> ConsolePrinter.printFail("Nomination rejected (Insufficient votes).");
            case -1 -> ConsolePrinter.printInfo("Voting is still in progress.");
        }
    }

    private void showNodeStatus() {
        Set<NodeConfig> nodes = Bootstrap.getInstance().getNodes();
        if (nodes.isEmpty()) {
            ConsolePrinter.printInfo("No nodes available.");
            return;
        }

        System.out.println("\nActive Nodes:");
        for (NodeConfig nodeConfig : nodes) {
            System.out.println(" - IP: " + nodeConfig.getIp()
                    + ", Role: " + nodeConfig.getRole()
                    + ", PublicKey: " + nodeConfig.getPublicKey().hashCode());
        }
    }

//    public static void main(String[] args) {
//        try {
//            Node node = new Node(null); // or pass private key
//            NodeJoin nodeJoin = new NodeJoin();
//            nodeJoin.register(node.networkManager);
//
//            NodeCLI cli = new NodeCLI(node, nodeJoin);
//            cli.start();
//
//        } catch (Exception e) {
//            ConsolePrinter.printFail("Failed to initialize CLI: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
}
