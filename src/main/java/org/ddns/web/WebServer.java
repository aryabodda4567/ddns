package org.ddns.web;

import com.google.gson.Gson;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.web.services.config.BootstrapHandler;
import org.ddns.web.services.config.JoiningHandler;
import org.ddns.web.services.config.infos;
import org.ddns.web.services.dns.DnsWebHandler;
import org.ddns.web.services.election.ElectionHandler;
import org.ddns.web.user.AuthHandler;
import org.ddns.web.user.SessionManager;
import org.ddns.web.user.User;

import java.security.Security;
import java.util.Set;

import static spark.Spark.before;
import static spark.Spark.exception;
import static spark.Spark.get;
import static spark.Spark.halt;
import static spark.Spark.ipAddress;
import static spark.Spark.port;
import static spark.Spark.post;
import static spark.Spark.staticFiles;

/**
 * Entry point for web route registration and HTTP security gating.
 *
 * <p>This server keeps the same route behavior while enforcing:
 * <ul>
 *     <li>Login requirement for protected pages/APIs</li>
 *     <li>First-time bootstrap access when no user is configured</li>
 *     <li>Accepted-node requirement for DNS CRUD and vote panels</li>
 * </ul>
 */
public final class WebServer {

    private static final Gson GSON = new Gson();

    private static final JoiningHandler JOINING_HANDLER = new JoiningHandler();
    private static final BootstrapHandler BOOTSTRAP_HANDLER = new BootstrapHandler();
    private static final AuthHandler AUTH_HANDLER = new AuthHandler();
    private static final infos INFO_SERVICE = new infos();

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/login.html",
            "/auth/login",
            "/auth/session",
            "/auth/logout"
    );

    private static final Set<String> BOOTSTRAP_PATHS = Set.of(
            "/join.html",
            "/join",
            "/checkfetchresult",
            "/join_result.html",
            "/election/result"
    );

    private static final Set<String> ACCEPTANCE_ONLY_HTML_PATHS = Set.of(
            "/index.html",
            "/create.html",
            "/update.html",
            "/delete.html",
            "/lookup.html",
            "/status.html",
            "/vote.html"
    );

    private WebServer() {
    }

    public static void start() {
        port(8080);
        ipAddress("0.0.0.0");
        staticFiles.location("/public");

        registerAccessFilter();
        registerGlobalExceptionHandlers();
        registerRoutes();
    }

    private static void registerAccessFilter() {
        before((request, response) -> {
            String path = request.pathInfo();
            if (path == null) {
                return;
            }

            if (isStaticAsset(path) || PUBLIC_PATHS.contains(path)) {
                return;
            }

            boolean userConfigured = User.getUser() != null;
            if (!userConfigured && BOOTSTRAP_PATHS.contains(path)) {
                return;
            }

            boolean loggedIn = SessionManager.isSessionValid(request);
            if (!loggedIn) {
                if ("GET".equalsIgnoreCase(request.requestMethod()) && path.endsWith(".html")) {
                    response.redirect("/login.html");
                    halt(302);
                    return;
                }

                response.status(401);
                response.type("application/json");
                response.body("{\"error\":\"Login required.\"}");
                halt(401);
                return;
            }

            if (!INFO_SERVICE.isAccepted()) {
                boolean blockedApi = path.startsWith("/dns/")
                        || "/election/vote".equals(path)
                        || "/election/nominations".equals(path);
                boolean blockedHtml = ACCEPTANCE_ONLY_HTML_PATHS.contains(path);

                if (blockedHtml) {
                    response.redirect("/home.html");
                    halt(302);
                    return;
                }

                if (blockedApi) {
                    response.status(403);
                    response.type("application/json");
                    response.body("{\"error\":\"Node not accepted yet. CRUD and vote panel are disabled.\"}");
                    halt(403);
                }
            }
        });
    }

    private static boolean isStaticAsset(String path) {
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/");
    }

    private static void registerGlobalExceptionHandlers() {
        exception(IllegalArgumentException.class, (error, request, response) -> {
            response.status(400);
            response.type("application/json");
            response.body("{\"error\":\"" + error.getMessage().replace("\"", "'") + "\"}");
        });

        exception(Exception.class, (error, request, response) -> {
            error.printStackTrace();
            response.status(500);
            response.type("application/json");
            response.body("{\"error\":\"Internal server error\"}");
        });
    }

    private static void registerRoutes() {
        post("/join", JOINING_HANDLER::handle, GSON::toJson);
        get("/checkfetchresult", BOOTSTRAP_HANDLER::handle, GSON::toJson);

        post("/auth/login", AUTH_HANDLER::login, GSON::toJson);
        get("/auth/session", AUTH_HANDLER::session, GSON::toJson);
        post("/auth/logout", AUTH_HANDLER::logout, GSON::toJson);

        ElectionHandler electionHandler = new ElectionHandler();
        post("/election/create-join", electionHandler::createJoinElection, GSON::toJson);
        get("/election/nominations", electionHandler::listNominations, GSON::toJson);
        post("/election/vote", electionHandler::castVote, GSON::toJson);
        post("/election/result", electionHandler::electionResult, GSON::toJson);

        DnsWebHandler dnsHandler = new DnsWebHandler();
        post("/dns/create", dnsHandler::create, GSON::toJson);
        post("/dns/delete", dnsHandler::delete, GSON::toJson);
        post("/dns/update", dnsHandler::update, GSON::toJson);

        get("/dns/lookup", dnsHandler::lookup, GSON::toJson);
        get("/dns/reverse", dnsHandler::reverse, GSON::toJson);
        get("/dns/status", dnsHandler::status, GSON::toJson);
    }

    public static void main(String[] args) {
        Security.addProvider(new BouncyCastleProvider());
        start();
    }
}
