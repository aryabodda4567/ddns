package org.ddns.web.services.dns;

import com.google.gson.Gson;
import org.ddns.bc.Transaction;
import org.ddns.bc.TransactionType;
import org.ddns.consensus.ConsensusEngine;
import org.ddns.db.DBUtil;
import org.ddns.db.DNSDb;
import org.ddns.db.TransactionDb;
import org.ddns.dns.DNSModel;
import org.ddns.dns.DNSServer;
import org.ddns.dns.RecordType;
import org.ddns.util.ConsolePrinter;
import org.sqlite.core.DB;
import spark.Request;
import spark.Response;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;

public class DnsWebHandler {

    private final Gson gson = new Gson();


    public Object create(Request request, Response response) {
        try {
            // 1. Parse JSON
            DNSModel input = gson.fromJson(request.body(), DNSModel.class);

            if (input == null) {
                response.status(400);
                return Map.of("error", "Invalid JSON body");
            }

            // 2. Fill server-side fields
            PublicKey publicKey = DBUtil.getInstance().getPublicKey();
            PrivateKey privateKey = DBUtil.getInstance().getPrivateKey();

            DNSModel model = new DNSModel(
                    input.getName(),
                    input.getType(),
                    input.getTtl(),
                    input.getRdata(),
                    publicKey,
                    null
            );

            // 3. Validate FIRST
            DNSValidator.validateForCreate(model);

            // 4. Enforce uniqueness per (name, type)
            if (DNSDb.getInstance().exists(model.getName(), model.getType())) {
                response.status(409); // Conflict
                return Map.of(
                        "error", "Record already exists for " + model.getName() + " type=" + model.getType()
                );
            }

            // 5. Build transaction
            Transaction transaction = new Transaction(
                    publicKey,
                    TransactionType.REGISTER,
                    List.of(model)
            );

            // 6. Sign transaction
            transaction.sign(privateKey);

            // 7. Fill tx hash into payload
            String txHash = transaction.getHash();
            for (DNSModel m : transaction.getPayload()) {
                m.setTransactionHash(txHash);
            }

            // 8. Publish to consensus
            ConsensusEngine.getInstance().publishTransaction(transaction);

            // 9. Respond OK
            response.status(200);
            return Map.of(
                    "status", "OK",
                    "message", "DNS record submitted to consensus",
                    "txHash", txHash
            );

        } catch (IllegalArgumentException e) {
            response.status(400);
            return Map.of("error", e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            response.status(500);
            return Map.of(
                    "error", "Internal server error",
                    "details", e.getMessage()
            );
        }
    }


    public Object lookup(Request request, Response response) {
        try {
            String name = request.queryParams("name");
            String typeStr = request.queryParams("type");

            if (name == null || name.isBlank()) {
                response.status(400);
                return Map.of("error", "Missing 'name' parameter");
            }

            int type;
            if (typeStr == null || typeStr.isBlank()) {
                // -1 means "any type"
                type = -1;
            } else {
                type = RecordType.fromString(typeStr);
            }

            // Call DNS server directly (no transaction)
            List<DNSModel> result = DNSServer.get().lookup(name, type);

            response.status(200);
            return Map.of(
                    "name", name,
                    "type", typeStr == null ? "ANY" : typeStr,
                    "count", result.size(),
                    "records", result
            );

        } catch (IllegalArgumentException e) {
            response.status(400);
            return Map.of("error", e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            response.status(500);
            return Map.of("error", "Internal server error");
        }
    }


    public Object reverse(Request request, Response response) {
        try {
            String value = request.queryParams("value");

            if (value == null || value.isBlank()) {
                response.status(400);
                return Map.of("error", "Missing 'value' parameter");
            }

            List<DNSModel> result = DNSServer.get().reverseLookup(value);

            response.status(200);
            return Map.of(
                    "value", value,
                    "count", result.size(),
                    "records", result
            );

        } catch (Exception e) {
            e.printStackTrace();
            response.status(500);
            return Map.of("error", "Internal server error");
        }
    }



    public Object delete(Request request, Response response) {
        try {
            DNSModel input = gson.fromJson(request.body(), DNSModel.class);

            if (input == null) {
                response.status(400);
                return Map.of("error", "Invalid JSON body");
            }

            if (input.getName() == null || input.getName().isBlank()) {
                response.status(400);
                return Map.of("error", "Missing name");
            }

            if (input.getRdata() == null || input.getRdata().isBlank()) {
                response.status(400);
                return Map.of("error", "Missing rdata");
            }

            PublicKey publicKey = DBUtil.getInstance().getPublicKey();
            PrivateKey privateKey = DBUtil.getInstance().getPrivateKey();

            // Build model with owner
            DNSModel model = new DNSModel(
                    input.getName(),
                    input.getType(),
                    0,
                    input.getRdata(),
                    publicKey,
                    null
            );

            if (!DNSDb.getInstance().exists(model.getName(), model.getType())) {
                response.status(400); // Conflict
                return Map.of(
                        "error", "Record does not exists for " + model.getName() + " type=" + model.getType()
                );
            }


            // Build transaction
            Transaction transaction = new Transaction(
                    publicKey,
                    TransactionType.DELETE_RECORDS,
                    List.of(model)
            );

            transaction.sign(privateKey);

            ConsensusEngine.getInstance().publishTransaction(transaction);

            response.status(200);
            return Map.of(
                    "status", "OK",
                    "message", "Delete request submitted to consensus",
                    "txHash", transaction.getHash()
            );

        } catch (Exception e) {
            e.printStackTrace();
            response.status(500);
            return Map.of("error", "Internal server error");
        }
    }

    public Object status(Request request, Response response) {
        try {
            String txHash = request.queryParams("txHash");

            if (txHash == null || txHash.isBlank()) {
                response.status(400);
                return Map.of("error", "Missing 'txHash' parameter");
            }

            TransactionDb.TransactionRow row =
                    TransactionDb.getInstance().readTransactionByHash(txHash);

            if (row == null) {
                response.status(404);
                return Map.of(
                        "txHash", txHash,
                        "found", false,
                        "message", "Transaction not found"
                );
            }

            response.status(200);
            return Map.of(
                    "txHash", txHash,
                    "found", true,
                    "transaction", row
            );

        } catch (Exception e) {
            e.printStackTrace();
            response.status(500);
            return Map.of("error", "Internal server error");
        }
    }

    public Object update(Request request, Response response) {
        try {
            // 1. Parse JSON
            DNSModel input = gson.fromJson(request.body(), DNSModel.class);

            if (input == null) {
                response.status(400);
                return Map.of("error", "Invalid JSON body");
            }

            // 2. Load keys
            PublicKey publicKey = DBUtil.getInstance().getPublicKey();
            PrivateKey privateKey = DBUtil.getInstance().getPrivateKey();

            DNSModel model = new DNSModel(
                    input.getName(),
                    input.getType(),
                    input.getTtl(),
                    input.getRdata(),
                    publicKey,
                    null
            );

            // 3. Validate
            DNSValidator.validateForUpdate(model);

            // 4. Enforce existence
            if (!DNSDb.getInstance().exists(model.getName(), model.getType())) {
                response.status(400);
                return Map.of(
                        "error", "Record does not exist for " + model.getName() + " type=" + model.getType()
                );
            }

            // 5. Build transaction
            Transaction transaction = new Transaction(
                    publicKey,
                    TransactionType.UPDATE_RECORDS,
                    List.of(model)
            );

            // 6. Sign transaction
            transaction.sign(privateKey);

            // 7. Attach tx hash to payload
            for (DNSModel m : transaction.getPayload()) {
                m.setTransactionHash(transaction.calculateHash());
            }

            // 8. Publish to consensus
            ConsensusEngine.getInstance().publishTransaction(transaction);

            // 9. Respond OK
            response.status(200);
            return Map.of(
                    "status", "OK",
                    "message", "DNS record update submitted to consensus",
                    "txHash", transaction.getHash()
            );

        } catch (IllegalArgumentException e) {
            response.status(400);
            return Map.of("error", e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            response.status(500);
            return Map.of(
                    "error", "Internal server error",
                    "details", e.getMessage()
            );
        }
    }





}
