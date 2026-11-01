package com.skcraft.autoauth.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.skcraft.autoauth.config.ModConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.Util;
import net.minecraft.client.User;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Clean-room Microsoft → Xbox → XSTS → Minecraft authentication. */
public final class MicrosoftAuth {
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Endpoints PRODUCTION = new Endpoints(
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
            "https://user.auth.xboxlive.com/user/authenticate", "https://xsts.auth.xboxlive.com/xsts/authorize",
            "https://api.minecraftservices.com/authentication/login_with_xbox", "https://api.minecraftservices.com/minecraft/profile");

    private MicrosoftAuth() {
    }

    public record AuthResult(String accessToken, String refreshToken, UUID uuid, String username) {
        public User toUser() {
            return new User(username, uuid, accessToken, Optional.empty(), Optional.empty(), User.Type.MSA);
        }

        @Override
        public String toString() {
            return "AuthResult[credentials redacted]";
        }
    }

    public static LoginOperation beginLogin() throws IOException {
        return new Client(ModConfig.clientId(), PRODUCTION, new HttpJson())
                .beginLogin(uri -> Util.getPlatform().openUri(uri), Duration.ofMinutes(3));
    }

    /** Compatibility helper. Callers needing explicit cancellation should own a LoginOperation. */
    public static AuthResult loginInteractive() throws Exception {
        try (LoginOperation operation = beginLogin()) {
            try {
                return operation.result().get();
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw new AuthFailure("Microsoft sign-in failed. Try again.", false);
            }
        }
    }

    public static AuthResult authenticateWithRefreshToken(String refreshToken) throws Exception {
        return new Client(ModConfig.clientId(), PRODUCTION, new HttpJson()).refresh(refreshToken);
    }

    // Constructor injection is package-private for tests; no runtime flag replaces production services.
    record Endpoints(String authorize, String token, String xbox, String xsts, String minecraft, String profile) {
    }

    static final class Client {
        private final String clientId;
        private final Endpoints endpoints;
        private final HttpJson http;

        Client(String clientId, Endpoints endpoints, HttpJson http) throws AuthFailure {
            if (clientId == null || clientId.isBlank()) {
                throw new AuthFailure("Microsoft login is unavailable. This installation has no configured login provider.", false);
            }
            this.clientId = clientId.trim();
            this.endpoints = endpoints;
            this.http = http;
        }

        LoginOperation beginLogin(Consumer<URI> browser, Duration timeout) throws IOException {
            return new LoginOperation(this, browser, timeout);
        }

        AuthResult refresh(String refreshToken) throws IOException, InterruptedException {
            if (blank(refreshToken)) {
                throw new AuthFailure("This account has no saved login. Add the Microsoft account again.", false);
            }
            return finish(tokens(http.postForm(endpoints.token(), Map.of("client_id", clientId, "grant_type", "refresh_token",
                    "refresh_token", refreshToken, "scope", SCOPE)), refreshToken));
        }

        AuthResult exchange(String code, String redirect, String verifier) throws IOException, InterruptedException {
            return finish(tokens(http.postForm(endpoints.token(), Map.of("client_id", clientId, "grant_type", "authorization_code",
                    "code", code, "redirect_uri", redirect, "scope", SCOPE, "code_verifier", verifier)), null));
        }

        private TokenPair tokens(JsonObject json, String previousRefresh) throws AuthFailure {
            String refresh = optionalString(json, "refresh_token");
            if (blank(refresh)) {
                refresh = previousRefresh;
            }
            if (blank(refresh)) {
                throw new AuthFailure("Microsoft did not provide a saved login. Sign in again and allow offline access.", false);
            }
            return new TokenPair(requiredString(json, "access_token"), refresh);
        }

        private AuthResult finish(TokenPair tokens) throws IOException, InterruptedException {
            checkInterrupted();
            JsonObject properties = new JsonObject();
            properties.addProperty("AuthMethod", "RPS");
            properties.addProperty("SiteName", "user.auth.xboxlive.com");
            properties.addProperty("RpsTicket", "d=" + tokens.access());
            JsonObject xbox = http.postJson(endpoints.xbox(), envelope(properties, "http://auth.xboxlive.com"),
                    Map.of("x-xbl-contract-version", "1"));
            String xboxToken = requiredString(xbox, "Token");
            JsonArray userTokens = new JsonArray();
            userTokens.add(xboxToken);
            properties = new JsonObject();
            properties.addProperty("SandboxId", "RETAIL");
            properties.add("UserTokens", userTokens);
            checkInterrupted();
            JsonObject xsts = http.postJson(endpoints.xsts(), envelope(properties, "rp://api.minecraftservices.com/"),
                    Map.of("x-xbl-contract-version", "1"));
            if (xsts.has("XErr")) {
                throw HttpJson.xboxFailure(xsts.get("XErr").isJsonPrimitive() ? xsts.get("XErr").getAsString() : null);
            }
            String xstsToken = requiredString(xsts, "Token");
            String hash;
            try {
                hash = requiredString(xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject(), "uhs");
            } catch (RuntimeException invalid) {
                throw invalidResponse();
            }
            JsonObject body = new JsonObject();
            body.addProperty("identityToken", "XBL3.0 x=" + hash + ";" + xstsToken);
            checkInterrupted();
            String access = requiredString(http.postJson(endpoints.minecraft(), body), "access_token");
            checkInterrupted();
            JsonObject profile = http.getJson(endpoints.profile(), Map.of("Authorization", "Bearer " + access));
            String name = requiredString(profile, "name");
            String raw = requiredString(profile, "id");
            if (!raw.matches("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
                throw invalidResponse();
            }
            if (raw.length() == 32) {
                raw = raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16) + "-"
                        + raw.substring(16, 20) + "-" + raw.substring(20);
            }
            return new AuthResult(access, tokens.refresh(), UUID.fromString(raw), name);
        }
    }

    /** Owns listener, auth worker and deadline. Closing also interrupts ongoing token exchanges. */
    public static final class LoginOperation implements AutoCloseable {
        private final CompletableFuture<AuthResult> result = new CompletableFuture<>();
        private final AtomicBoolean consumed = new AtomicBoolean();
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final List<HttpServer> servers;
        private final ExecutorService callbacks = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("autoauth-callback-", 0).factory());
        private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("autoauth-login-", 0).factory());
        private final ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("autoauth-deadline-", 0).factory());
        private final String state = randomSecret();
        private final String verifier = randomSecret();
        private final Client client;
        private final String redirect;

        private LoginOperation(Client client, Consumer<URI> browser, Duration timeout) throws IOException {
            this.client = client;
            servers = bindLoopbackListeners();
            redirect = "http://localhost:" + servers.getFirst().getAddress().getPort() + "/";
            result.whenComplete((ignored, error) -> cleanup());
            try {
                for (HttpServer server : servers) {
                    server.setExecutor(callbacks);
                    server.createContext("/", this::callback);
                    server.start();
                }
                deadline.schedule(() -> fail(new AuthFailure("Microsoft sign-in timed out. Try again.", true)),
                        timeout.toMillis(), TimeUnit.MILLISECONDS);
                browser.accept(URI.create(client.endpoints.authorize() + "?client_id=" + encode(client.clientId)
                        + "&response_type=code&redirect_uri=" + encode(redirect) + "&scope=" + encode(SCOPE)
                        + "&prompt=select_account&state=" + encode(state) + "&code_challenge_method=S256&code_challenge=" + challenge(verifier)));
            } catch (RuntimeException failure) {
                fail(new AuthFailure("Could not open Microsoft sign-in in the browser. Try again.", false));
            }
        }

        public CompletableFuture<AuthResult> result() {
            return result;
        }

        private void callback(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"/".equals(exchange.getRequestURI().getPath()) || !"GET".equals(exchange.getRequestMethod())) {
                    reply(exchange, 404, "Not found.");
                    return;
                }
                Map<String, String> params;
                try {
                    params = parseQuery(exchange.getRequestURI().getRawQuery());
                } catch (IllegalArgumentException malformed) {
                    reply(exchange, 400, "Invalid sign-in callback.");
                    return;
                }
                if (!state.equals(params.get("state")) || (!params.containsKey("error") && blank(params.get("code")))) {
                    reply(exchange, 400, "Invalid sign-in callback.");
                    return;
                }
                if (result.isDone() || !consumed.compareAndSet(false, true)) {
                    reply(exchange, 409, "This sign-in callback has already been received.");
                    return;
                }
                try {
                    if (params.containsKey("error")) {
                        reply(exchange, 200, "Authorization was declined. Return to Minecraft.");
                        fail(new AuthFailure("Microsoft sign-in was declined. Try again when ready.", false));
                        return;
                    }
                    reply(exchange, 200, "Authorization received. Minecraft is checking your account. Return to the game for the result.");
                    worker.execute(() -> exchange(params.get("code")));
                } catch (IOException | RejectedExecutionException failure) {
                    // Closing/replacing the screen can race an accepted HTTP callback.
                    if (!result.isDone()) {
                        fail(new AuthFailure("Microsoft sign-in could not finish. Try again.", true));
                    }
                }
            }
        }

        private void exchange(String code) {
            servers.forEach(server -> server.stop(0));
            callbacks.shutdown();
            try {
                if (!result.isDone()) {
                    AuthResult authenticated = client.exchange(code, redirect, verifier);
                    cleanup();
                    result.complete(authenticated);
                }
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                cleanup();
                result.cancel(false);
            } catch (AuthFailure failure) {
                fail(failure);
            } catch (Exception failure) {
                fail(new AuthFailure("Microsoft sign-in failed. Try again later.", true));
            }
        }

        @Override
        public void close() {
            cleanup();
            result.cancel(false);
        }

        private void fail(AuthFailure failure) {
            cleanup();
            result.completeExceptionally(failure);
        }

        private void cleanup() {
            if (cleaned.compareAndSet(false, true)) {
                servers.forEach(server -> server.stop(0));
                callbacks.shutdownNow();
                worker.shutdownNow();
                deadline.shutdownNow();
            }
        }
    }

    /** Browsers and the JVM can prefer different localhost address families. Accept both on one port. */
    private static List<HttpServer> bindLoopbackListeners() throws IOException {
        boolean ipv6Available;
        try {
            HttpServer probe = HttpServer.create(new InetSocketAddress("::1", 0), 0);
            probe.stop(0);
            ipv6Available = true;
        } catch (IOException | UnsupportedOperationException unavailable) {
            ipv6Available = false;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            HttpServer ipv4 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            if (!ipv6Available) {
                return List.of(ipv4);
            }
            try {
                HttpServer ipv6 = HttpServer.create(new InetSocketAddress("::1", ipv4.getAddress().getPort()), 0);
                return List.of(ipv4, ipv6);
            } catch (BindException occupiedOnIpv6) {
                ipv4.stop(0);
                // An ephemeral IPv4 port may already be occupied on IPv6. Pick another, never bind a wildcard.
            } catch (IOException | RuntimeException failure) {
                ipv4.stop(0);
                throw new AuthFailure("Could not start the local Microsoft sign-in callback. Try again.", true);
            }
        }
        throw new AuthFailure("Could not reserve a local Microsoft sign-in callback port. Try again.", true);
    }

    private static JsonObject envelope(JsonObject properties, String relyingParty) {
        JsonObject body = new JsonObject();
        body.add("Properties", properties);
        body.addProperty("RelyingParty", relyingParty);
        body.addProperty("TokenType", "JWT");
        return body;
    }

    private static String requiredString(JsonObject json, String key) throws AuthFailure {
        String value = optionalString(json, key);
        if (blank(value)) {
            throw invalidResponse();
        }
        return value;
    }

    private static String optionalString(JsonObject json, String key) {
        try {
            return json != null && json.has(key) && json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isString()
                    ? json.get(key).getAsString() : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static AuthFailure invalidResponse() {
        return new AuthFailure("The sign-in service returned an incomplete response. Try again later.", true);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Sign-in cancelled");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isBlank()) {
            return result;
        }
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Invalid query");
            }
            String key = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
            if (result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate query parameter");
            }
        }
        return result;
    }

    private static void reply(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = ("<!doctype html><html><body><p>" + message + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record TokenPair(String access, String refresh) {
    }
}
