package org.ddns.web;

import com.google.gson.Gson;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.web.services.config.BootstrapHandler;
import org.ddns.web.services.config.JoiningHandler;
import org.ddns.web.services.config.AppMode;
import org.ddns.web.services.config.AppModeStore;
import org.ddns.web.services.config.ModeHandler;
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
 * <p>
 * Three mode states drive access control:
 * <ul>
 * <li><b>UNSET</b> – only {@code GET /} (the setup chooser) is accessible.</li>
 * <li><b>BOOTSTRAP</b> – only bootstrap dashboard pages are accessible;
 * all chain-node pages are blocked.</li>
 * <li><b>NODE</b> – bootstrap pages are blocked; chain-node login/control
 * flow is accessible with session enforcement.</li>
 * </ul>
 */
public final class WebServer {

    private static final Gson GSON = new Gson();

    private static final JoiningHandler JOINING_HANDLER = new JoiningHandler();
    private static final BootstrapHandler BOOTSTRAP_HANDLER = new BootstrapHandler();
    private static final ModeHandler MODE_HANDLER = new ModeHandler();
    private static final AuthHandler AUTH_HANDLER = new AuthHandler();
    private static final infos INFO_SERVICE = new infos();

    // Paths accessible in BOOTSTRAP mode only
    private static final Set<String> BOOTSTRAP_MODE_ALLOWED_PATHS = Set.of(
            "/",
            "/bootstrap.html",
            "/mode",
            "/mode/select",
            "/mode/bootstrap/setup",
            "/mode/bootstrap/status",
            "/bootstrap/nodes",
            "/auth/session");

    // Paths accessible in NODE mode without a valid session
    // (join flow + auth endpoints)
    private static final Set<String> NODE_PUBLIC_PATHS = Set.of(
            "/",
            "/join.html",
            "/join",
            "/checkfetchresult",
            "/join_result.html",
            "/login.html",
            "/auth/login",
            "/auth/session",
            "/auth/logout",
            "/election/result",
            "/mode",
            "/mode/select");

    // HTML pages that require node acceptance (not just any logged-in session)
    private static final Set<String> ACCEPTANCE_ONLY_HTML_PATHS = Set.of(
            "/control.html",
            "/create.html",
            "/update.html",
            "/delete.html",
            "/lookup.html",
            "/status.html",
            "/vote.html");

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

            // Static assets are always allowed
            if (isStaticAsset(path)) {
                return;
            }

            AppMode mode = AppModeStore.getMode();

            // ── UNSET: only the root chooser page is allowed ──────────────────────
            if (mode == AppMode.UNSET) {
                if ("/".equals(path) || "/mode".equals(path) || "/mode/select".equals(path)) {
                    return;
                }

                if ("GET".equalsIgnoreCase(request.requestMethod()) && path.endsWith(".html")) {
                    response.redirect("/");
                    halt(302);
                    return;
                }

                response.status(403);
                response.type("application/json");
                response.body("{\"error\":\"Node not configured. Please select a startup mode first.\"}");
                halt(403);
                return;
            }

            // ── BOOTSTRAP mode ────────────────────────────────────────────────────
            if (mode == AppMode.BOOTSTRAP) {
                if (BOOTSTRAP_MODE_ALLOWED_PATHS.contains(path)) {
                    return;
                }

                if ("GET".equalsIgnoreCase(request.requestMethod()) && path.endsWith(".html")) {
                    response.redirect("/bootstrap.html");
                    halt(302);
                    return;
                }

                response.status(403);
                response.type("application/json");
                response.body("{\"error\":\"Bootstrap mode: only the bootstrap dashboard is available.\"}");
                halt(403);
                return;
            }

            // ── NODE mode ─────────────────────────────────────────────────────────

            // Block any bootstrap endpoint from chain-node context
            if (path.startsWith("/bootstrap")) {
                response.status(403);
                response.type("application/json");
                response.body("{\"error\":\"Bootstrap resources are not available in chain node mode.\"}");
                halt(403);
                return;
            }

            // Allow public / join-flow paths without a session
            if (NODE_PUBLIC_PATHS.contains(path)) {
                return;
            }

            // Session enforcement for all other NODE paths
            boolean loggedIn = SessionManager.isSessionValid(request);
            if (!loggedIn) {
                if ("GET".equalsIgnoreCase(request.requestMethod()) && path.endsWith(".html")) {
                    response.redirect("/login.html?next=" + path);
                    halt(302);
                    return;
                }

                response.status(401);
                response.type("application/json");
                response.body("{\"error\":\"Login required.\"}");
                halt(401);
                return;
            }

            // Acceptance gate: some pages/APIs require the node to be accepted
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
        // Root redirect based on current mode
        get("/", (req, res) -> {
            AppMode mode = AppModeStore.getMode();
            if (mode == AppMode.BOOTSTRAP) {
                res.redirect("/bootstrap.html");
            } else if (mode == AppMode.NODE) {
                // If user is already joined & session valid → home; otherwise join flow
                boolean loggedIn = SessionManager.isSessionValid(req);
                if (loggedIn) {
                    res.redirect("/home.html");
                } else {
                    res.redirect("/join.html");
                }
            }
            // UNSET: serve index.html (the chooser) — Spark staticFiles handles it
            return "";
        });

        get("/mode", MODE_HANDLER::getMode, GSON::toJson);
        post("/mode/select", MODE_HANDLER::selectMode, GSON::toJson);
        get("/mode/bootstrap/status", MODE_HANDLER::bootstrapStatus, GSON::toJson);
        post("/mode/bootstrap/setup", MODE_HANDLER::setupBootstrap, GSON::toJson);
        get("/bootstrap/nodes", MODE_HANDLER::listBootstrapNodes, GSON::toJson);

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
