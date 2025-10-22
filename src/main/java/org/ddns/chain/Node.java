package org.ddns.chain;

import org.ddns.NodeCLI;
import org.ddns.bc.*; // Import Block, Blockchain, Transaction, etc.
import org.ddns.chain.governance.NodeJoin;
import org.ddns.chain.governance.Nomination;
import org.ddns.db.DBUtil; // Use DBUtil for persistence
import org.ddns.net.*; // Import networking classes
import org.ddns.util.*; // Import utilities

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a blockchain node responsible for initialization, networking,
 * consensus, governance, and user interaction. Uses DBUtil for persistence.
 */
public class Node implements Bootstrap.NodeBootstrapListener, NodeJoin.NodeJoinListener, MessageHandler {

    // --- Core Components ---
    private final NetworkManager networkManager;
    private final Bootstrap bootstrap;
    private final DBUtil dbUtil;
    private Blockchain blockchain; // Initialized lazily after bootstrap/join
    private final NodeJoin nodeJoin;
    private final NodeInitializer nodeInitializer;

    // --- Node State ---
    private final PublicKey publicKey;
    private volatile Role role; // Volatile as it can be updated by listener callbacks
    private final String ownIpAddress;
    private final AtomicBoolean isBlockchainInitialized = new AtomicBoolean(false); // Tracks if blockchain obj is ready
    private final AtomicBoolean isCliStarted = new AtomicBoolean(false); // Prevent multiple CLI starts

    // --- Consensus Fields ---
    private final ScheduledExecutorService consensusScheduler = Executors.newSingleThreadScheduledExecutor();
    // Block interval in seconds (make this configurable later if needed)
    private static final long BLOCK_INTERVAL_SECONDS = 10;
    // Timeout threshold (e.g., 1.5 times the interval)
    private static final long LEADER_TIMEOUT_MS = (long) (BLOCK_INTERVAL_SECONDS * 1000 * 1.5);
    private volatile long lastBlockTimestamp; // Tracks time of last valid block for timeout detection

    /**
     * Constructs a new Node. Loads/creates wallet, initializes components.
     * @param privateKeyStrInput Optional Base64 private key string. If null, loads from DB or creates new.
     */
    public Node(String privateKeyStrInput) throws Exception {

        PrivateKey privateKey;
        this.dbUtil = DBUtil.getInstance();
        this.ownIpAddress = NetworkUtility.getLocalIpAddress();

        if(privateKeyStrInput == null){
            KeyPair keyPair = Wallet.getKeyPair();
            privateKey = keyPair.getPrivate();
            publicKey = keyPair.getPublic();
        }else{
            privateKey = SignatureUtil.getPrivateKeyFromString(privateKeyStrInput);
            publicKey = Wallet.getPublicKeyFromPrivateKey(privateKey);
        }
        DBUtil.getInstance().saveKeys(publicKey,privateKey);

        ConsolePrinter.printInfo("  Public Key (Short): " + SignatureUtil.getStringFromKey(publicKey).substring(0, 30) + "...");

        // --- Initialize Core Components ---
        ConsolePrinter.printInfo("[Node] Initializing Network & Modules...");
        this.networkManager = new NetworkManager();
        this.bootstrap = Bootstrap.getInstance(); // Use singleton
        this.nodeJoin = new NodeJoin();
        this.nodeInitializer = new NodeInitializer();

        // --- Register Handlers with NetworkManager ---
        // Node handles general messages like BLOCK, TRANSACTION
        // Bootstrap handles BOOTSTRAP_*, ADD_NODE_*
        // NodeJoin handles NOMINATION_*, CAST_VOTE
        networkManager.registerHandler(this);
        bootstrap.register(networkManager);
        nodeJoin.register(networkManager);

        // Set listeners for callbacks from Bootstrap and NodeJoin
        bootstrap.setListener(this);
        nodeJoin.setListener(this);
        networkManager.startListeners();
        ConsolePrinter.printSuccess("✓ Core components initialized.");
    }

    /**
     * Starts the node's main operations: listeners and bootstrap process.
     */
    public void start() {
        ConsolePrinter.printInfo("[Node] Starting network listeners...");

        ConsolePrinter.printInfo("[Node] Starting bootstrap process...");
        startBootstrapProcess();
        // CLI is started later in onBootstrapNodesReceived after role is potentially determined
        // Consensus (block production/timeout check) loop starts if/when node becomes leader or joins
    }

    /**
     * Initiates the bootstrap process to find other nodes in the network.
     */
    public void startBootstrapProcess() {
        // Asks the configured bootstrap node for the list of known nodes
        // Start the CLI only once, after bootstrap is done
//        startCLI();
        bootstrap.createNodesRequest();
    }

    // --- Bootstrap Listener Callback ---
    @Override
    public void onBootstrapNodesReceived(Set<NodeConfig> nodes) {
        ConsolePrinter.printInfo("[Node] Bootstrap response processed.");
        try {
            // Re-fetch from DB to confirm persistence and get the definite list
            Set<NodeConfig> knownNodes = dbUtil.getAllNodes();



            if (knownNodes == null || knownNodes.isEmpty()) {
                ConsolePrinter.printInfo("[Node] No existing nodes found in the network via Bootstrap.");
                // --- Become Genesis Node ---
                if (role != Role.GENESIS) { // Prevent re-initializing if already Genesis
                    nodeInitializer.initGenesisNode(); // Saves GENESIS role to DB & broadcasts ADD_NODE
                    this.role = Role.GENESIS;          // Update local state immediately
                }
                // Initialize blockchain for Genesis (only leader is self)
                if (blockchain == null) {
                    this.blockchain = new Blockchain(List.of(this.publicKey));
                    this.lastBlockTimestamp = System.currentTimeMillis(); // Initialize consensus timer
                    isBlockchainInitialized.set(true);
                    ConsolePrinter.printSuccess("✓ Blockchain initialized for Genesis node.");
                    startConsensusScheduler(); // Start producing blocks and checking timeouts
                } else {
                    ConsolePrinter.printInfo("[Node] Blockchain already initialized (likely restarted as Genesis).");
                    // Ensure scheduler is running if it wasn't
                    if (consensusScheduler.isShutdown()) {
                        startConsensusScheduler();
                    }
                }

            } else {
                ConsolePrinter.printInfo("[Node] Existing network found (" + knownNodes.size() + " nodes). Attempting to join...");
                // Role remains what was loaded from DB (e.g., NONE, or NORMAL if rejoining)

                // If already part of the network (e.g., NORMAL_NODE from DB), initialize blockchain and start checker
                if (role == Role.NORMAL_NODE && !isBlockchainInitialized.get()) {
                    initializeBlockchainFromKnownLeaders();
                    startConsensusScheduler();
                }
                // If role is NONE (or another non-member role), initiate join process
                else if (role == Role.NONE || role == null) { // Check for null role just in case
                    nodeJoin.createNominationRequest(1); // Start 1-minute join vote
                    System.out.println("Waiting");
                  //  TimeUtil.waitForSeconds(30);
                    System.out.println("Wait over");
                    nodeJoin.processResult();

                } else {
                    ConsolePrinter.printInfo("[Node] Already part of the network as " + role + ". Monitoring consensus.");
                    // Ensure blockchain is initialized and scheduler is running if needed
                    if (!isBlockchainInitialized.get()) initializeBlockchainFromKnownLeaders();
                    if (consensusScheduler.isShutdown()) startConsensusScheduler();
                }
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[Node] Critical error during bootstrap processing: " + e.getMessage());
            e.printStackTrace();
            shutdown(); // Stop node on critical failure
        }
    }

    // --- NodeJoin Listener Callbacks ---
    @Override
    public void onNodeJoinWin() {
        ConsolePrinter.printSuccess("[Node] 🎉 Join request APPROVED! Node is now part of the network.");
        if (role != Role.NORMAL_NODE) { // Update only if role changes
            this.role = Role.NORMAL_NODE;
            dbUtil.saveRole(this.role);
            nodeInitializer.initNormalNode(); // Announce self as Normal Node
        }
        // --- Initialize Blockchain and Start Timeout Checker ---
        if (!isBlockchainInitialized.get()) {
            ConsolePrinter.printInfo("[Node] Initiating state synchronization (Not Implemented - using bootstrap leaders)...");
            initializeBlockchainFromKnownLeaders();
            startConsensusScheduler(); // Start checking timeouts (won't produce blocks)
        }
    }

    @Override
    public void onNodeJoinLose() {
        ConsolePrinter.printFail("[Node] ❌ Join request REJECTED.");
        this.role = Role.NONE;
        dbUtil.saveRole(this.role);
        ConsolePrinter.printInfo("[Node] Shutting down due to join rejection.");
        shutdown();
    }

    @Override
    public void onNodeJoinProgress() {
        ConsolePrinter.printInfo("[Node] 🕓 Join vote still in progress...");
    }

    // --- Blockchain Initialization Helper ---
    private void initializeBlockchainFromKnownLeaders() {
        if (isBlockchainInitialized.get()) return; // Already done

        List<PublicKey> leaders = new ArrayList<>();
        dbUtil.getAllNodes().forEach(nc -> {
            if (nc.getRole() == Role.LEADER_NODE || nc.getRole() == Role.GENESIS) {
                leaders.add(nc.getPublicKey());
            }
        });
        if (!leaders.isEmpty()) {
            this.blockchain = new Blockchain(leaders);
            this.lastBlockTimestamp = System.currentTimeMillis(); // Init timer
            isBlockchainInitialized.set(true);
            ConsolePrinter.printSuccess("✓ Blockchain initialized based on known leaders.");
            // TODO: Add real sync logic here later
        } else {
            ConsolePrinter.printWarning("⚠️ Could not determine leaders from DB. Blockchain not initialized.");
            // Maybe retry bootstrap? Or wait for leader announcements?
        }
    }


    // --- Consensus Scheduler (Block Production & Timeout Check) ---

    /**
     * Starts the scheduled task for block production (if leader) and timeout checks.
     */
    public void startConsensusScheduler() {
        if (!consensusScheduler.isShutdown()) {
            // Already running or starting
            return;
        }
        // Recreate scheduler if it was previously shut down
        // ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // this.consensusScheduler = scheduler; // Needs field to be non-final or use getter/setter


        Runnable consensusTask = () -> {
            try {
                if (!isBlockchainInitialized.get()) {
                    // ConsolePrinter.printInfo("[Consensus] Waiting for blockchain initialization...");
                    return; // Don't run consensus logic until ready
                }

                // Check for Timeout FIRST (all nodes do this)
                checkLeaderTimeout();

                // Leader-specific actions
                if (role == Role.LEADER_NODE || role == Role.GENESIS) {
                    // Check if it's my turn
                    if (blockchain.isLeaderTurn(this.publicKey)) {
                        ConsolePrinter.printInfo("[Consensus] My turn! Producing block...");
                        Block newBlock = blockchain.createBlock(this.publicKey); // Handles internal logic

                        if (newBlock != null) {
                            String jsonBlock = ConversionUtil.toJson(newBlock);
                            Message blockMessage = new Message(MessageType.BLOCK, ownIpAddress, this.publicKey, jsonBlock);
                            String jsonNetworkMessage = ConversionUtil.toJson(blockMessage);
                            NetworkManager.broadcast(jsonNetworkMessage);
                            ConsolePrinter.printSuccess("✓ Broadcasted new block: " + newBlock.getHash().substring(0, 10) + "...");
                            this.lastBlockTimestamp = System.currentTimeMillis(); // Reset timer on success
                        } else {
                            ConsolePrinter.printWarning("[Consensus] Failed to create block even though it's my turn.");
                        }
                    }
                    // Else: Not my turn, already checked timeout above.
                }
            } catch (Exception e) {
                ConsolePrinter.printFail("[Consensus] CRITICAL Error during consensus task: " + e.getMessage());
                e.printStackTrace();
            }
        };

        consensusScheduler.scheduleAtFixedRate(
                consensusTask,
                BLOCK_INTERVAL_SECONDS, // Initial delay
                BLOCK_INTERVAL_SECONDS, // Interval
                TimeUnit.SECONDS);

        ConsolePrinter.printSuccess("✓ Consensus scheduler started (Interval: " + BLOCK_INTERVAL_SECONDS + "s).");
    }

    /**
     * Checks if the expected leader has produced a block within the timeout period.
     */
    private void checkLeaderTimeout() {
        if (!isBlockchainInitialized.get()) return;

        long timeSinceLastBlock = System.currentTimeMillis() - lastBlockTimestamp;

        if (timeSinceLastBlock > LEADER_TIMEOUT_MS) {
            ConsolePrinter.printWarning("[Consensus] Leader timeout detected! Advancing to next leader.");
            blockchain.advanceToNextLeader();
            this.lastBlockTimestamp = System.currentTimeMillis(); // Reset timer after advancing
        }
    }

    /**
     * Stops the consensus scheduler.
     */
    public void stopConsensusScheduler() {
        if (!consensusScheduler.isShutdown()) {
            consensusScheduler.shutdown();
            try {
                // Wait a bit for tasks to finish executing
                if (!consensusScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    consensusScheduler.shutdownNow(); // Force shutdown if tasks don't finish
                }
            } catch (InterruptedException ie) {
                consensusScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            ConsolePrinter.printWarning("[Node] Consensus scheduler stopped.");
        }
    }

    // --- MessageHandler Interface Implementation ---

    @Override
    public void onBroadcastMessage(String message) {
        // Allow discovery/join messages before blockchain is initialized
        boolean allowBeforeInit = false;
        Message tempMsg = null;
        try { // Quick peek at message type without full parsing if not needed
            tempMsg = ConversionUtil.fromJson(message, Message.class);
            if (tempMsg != null && (tempMsg.type == MessageType.DISCOVERY_REQUEST /* add other allowed types here */)) {
                allowBeforeInit = true;
            }
        } catch (Exception ignored) {} // Ignore if parsing fails here

        if (!isBlockchainInitialized.get() && !allowBeforeInit) {
            ConsolePrinter.printWarning("[Node] Ignoring broadcast: Blockchain not yet initialized. Type: " + (tempMsg != null ? tempMsg.type : "Unknown"));
            return;
        }

        try {
            Message msg = (tempMsg != null) ? tempMsg : ConversionUtil.fromJson(message, Message.class); // Use parsed msg if available
            if (msg == null) return;
            ConsolePrinter.printInfo("[BROADCAST] Received " + msg.type + " from " + msg.senderIp);

            switch (msg.type) {
                case BLOCK:
                    if (isBlockchainInitialized.get()) {
                        Block receivedBlock = ConversionUtil.fromJson(msg.payload, Block.class);
                        if (receivedBlock != null && blockchain.addBlock(receivedBlock)) { // addBlock handles DB save & state
                            ConsolePrinter.printSuccess("✓ Valid block added: " + receivedBlock.getHash().substring(0, 10));
                            this.lastBlockTimestamp = System.currentTimeMillis(); // Reset timeout timer
                        } else {
                            ConsolePrinter.printWarning("⚠️ Invalid block received. Ignoring.");
                        }
                    } else {
                        ConsolePrinter.printWarning("[Node] Ignoring BLOCK: Blockchain not initialized.");
                    }
                    break;
                case TRANSACTION: // Handle DNS transactions broadcasted
                    if (isBlockchainInitialized.get()) {
                        // Deserialize the *actual* Transaction from the message payload's "TRANSACTION" field
                        Map<String, String> payloadMap = ConversionUtil.jsonToMap(msg.payload);
                        if (payloadMap != null && payloadMap.containsKey("TRANSACTION")) {
                            Transaction receivedTx = ConversionUtil.fromJson(payloadMap.get("TRANSACTION"), Transaction.class);
                            if (receivedTx != null && blockchain.addTransaction(receivedTx)) {
                                ConsolePrinter.printInfo("[Node] Added pending transaction: " + receivedTx.getHash().substring(0,10));
                            } else {
                                ConsolePrinter.printWarning("⚠️ Invalid or failed transaction received. Ignoring.");
                            }
                        } else {
                            ConsolePrinter.printWarning("⚠️ Malformed TRANSACTION message payload.");
                        }
                    } else {
                        ConsolePrinter.printWarning("[Node] Ignoring TRANSACTION: Blockchain not initialized.");
                    }
                    break;
                // --- Delegate other broadcast types if needed ---
                // case ADD_NODE: // Handled by Bootstrap registered handler
                // case NOMINATION_REQUEST: // Handled by NodeJoin registered handler
                // default:
                //     ConsolePrinter.printInfo("[Node] Broadcast type " + msg.type + " likely handled by another module.");
                //     break;
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[Node] Error processing broadcast message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onDirectMessage(String message) {
        // Direct messages are handled by Bootstrap and NodeJoin as registered handlers.
        // This node only needs to log receipt for debugging.
        ConsolePrinter.printInfo("[DIRECT] Received (handled by Bootstrap/NodeJoin): " + message.substring(0, Math.min(message.length(), 100)) + "...");
    }

    @Override
    public void onMulticastMessage(String message) {
        // This is for leader-specific communication
        if (role == Role.LEADER_NODE || role == Role.GENESIS) {
            ConsolePrinter.printInfo("[MULTICAST] Received (for leaders): " + message.substring(0, Math.min(message.length(), 100)) + "...");
            // TODO: Implement leader-specific logic (e.g., advanced consensus steps)
        } else {
            // Ignore if not a leader
        }
    }

    // --- Utility Methods ---

    /** Starts the CLI in a separate thread, ensuring it only starts once. */
    private void startCLI() {
        if (isCliStarted.compareAndSet(false, true)) { // Atomically start only once
            NodeCLI nodeCLI = new NodeCLI(this, nodeJoin);
            new Thread(nodeCLI::start).start();
            ConsolePrinter.printSuccess("✓ Command Line Interface started.");
        }
    }

    /** Gracefully shuts down the node. */
    public void shutdown() {
        ConsolePrinter.printWarning("[Node] Shutting down...");
        stopConsensusScheduler();
        if (networkManager != null) networkManager.stop();
        // Close DB connection pool if DBUtil implements Closeable
        System.exit(0);
    }

    // --- Getters for CLI and other components ---
    public PublicKey getPublicKey() {
        return publicKey;
    }
    public PrivateKey getPrivateKey() throws Exception { // Needed for CLI to sign transactions
        return DBUtil.getInstance().getPrivateKey();
    }
    public Role getRole() {
        return role;
    }
    public Blockchain getBlockchain() { // Allow CLI to access blockchain state/methods
        return blockchain;
    }
    public DBUtil getDbUtil() { // Allow CLI to access DB directly if needed
        return dbUtil;
    }
    public NetworkManager getNetworkManager() { // Allow CLI to send messages directly
        return networkManager;
    }
    public Bootstrap getBootstrap() { // Allow CLI access if needed
        return bootstrap;
    }
}
