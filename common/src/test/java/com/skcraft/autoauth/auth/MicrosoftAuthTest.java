package com.skcraft.autoauth.auth;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class MicrosoftAuthTest {
    private static final UUID PROFILE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final HttpClient BROWSER = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void refreshTraversesTheActualHttpChainAndRetainsTheFinalRotatedToken() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var result = fixture.client().refresh("old-refresh +/&");
            assertEquals(PROFILE_ID, result.uuid());
            assertEquals("FixturePlayer", result.username());
            assertEquals("minecraft-access", result.accessToken());
            assertEquals("rotated-refresh", result.refreshToken());
            assertEquals(List.of("/token", "/xbox", "/xsts", "/minecraft", "/profile"), fixture.requests.stream().map(Request::path).toList());
            Map<String, String> token = MicrosoftAuth.parseQuery(fixture.request("/token").body());
            assertEquals("fixture-client", token.get("client_id"));
            assertEquals("refresh_token", token.get("grant_type"));
            assertEquals("old-refresh +/&", token.get("refresh_token"));
            assertEquals("XboxLive.signin offline_access", token.get("scope"));
            var xbox = JsonParser.parseString(fixture.request("/xbox").body()).getAsJsonObject();
            assertEquals("d=microsoft-access", xbox.getAsJsonObject("Properties").get("RpsTicket").getAsString());
            assertEquals("RPS", xbox.getAsJsonObject("Properties").get("AuthMethod").getAsString());
            assertEquals("http://auth.xboxlive.com", xbox.get("RelyingParty").getAsString());
            var xsts = JsonParser.parseString(fixture.request("/xsts").body()).getAsJsonObject();
            assertEquals("RETAIL", xsts.getAsJsonObject("Properties").get("SandboxId").getAsString());
            assertEquals("xbox-token", xsts.getAsJsonObject("Properties").getAsJsonArray("UserTokens").get(0).getAsString());
            assertEquals("rp://api.minecraftservices.com/", xsts.get("RelyingParty").getAsString());
            assertEquals("XBL3.0 x=user-hash;xsts-token", JsonParser.parseString(fixture.request("/minecraft").body())
                    .getAsJsonObject().get("identityToken").getAsString());
            assertEquals("Bearer minecraft-access", fixture.request("/profile").authorization());
            assertFalse(result.toString().contains("minecraft-access"));
            assertFalse(result.toString().contains("rotated-refresh"));
        }
    }

    @Test
    void refreshResponseWithoutRotationPreservesTheExistingRefreshToken() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply("/token", 200, "{\"access_token\":\"microsoft-access\"}");
            assertEquals("existing-refresh", fixture.client().refresh("existing-refresh").refreshToken());
        }
    }

    @Test
    void codeFlowUsesPkceAndDoesNotImmediatelyDiscardTheCodeExchangeTokens() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<URI> authorize = new AtomicReference<>();
            try (var operation = fixture.client().beginLogin(authorize::set, Duration.ofSeconds(5))) {
                Map<String, String> query = MicrosoftAuth.parseQuery(authorize.get().getRawQuery());
                assertEquals("code", query.get("response_type"));
                assertEquals("select_account", query.get("prompt"));
                assertEquals("S256", query.get("code_challenge_method"));
                assertEquals("XboxLive.signin offline_access", query.get("scope"));
                assertEquals(200, get(callback(query, "state=" + query.get("state") + "&code=valid%2Bcode%2F")).statusCode());
                var result = operation.result().get(5, TimeUnit.SECONDS);
                assertEquals(PROFILE_ID, result.uuid());
                Map<String, String> token = MicrosoftAuth.parseQuery(fixture.request("/token").body());
                assertEquals("authorization_code", token.get("grant_type"));
                assertEquals("valid+code/", token.get("code"));
                assertEquals(query.get("redirect_uri"), token.get("redirect_uri"));
                assertEquals(query.get("code_challenge"), MicrosoftAuth.challenge(token.get("code_verifier")));
                assertTrue(token.get("code_verifier").matches("[A-Za-z0-9_-]{43,128}"));
                assertEquals(1L, fixture.requests.stream().filter(request -> request.path().equals("/token")).count());
                assertListenerClosed(query);
            }
        }
    }

    @Test
    void invalidCallbacksDoNotConsumeAValidLogin() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<URI> authorize = new AtomicReference<>();
            try (var operation = fixture.client().beginLogin(authorize::set, Duration.ofSeconds(5))) {
                var query = MicrosoftAuth.parseQuery(authorize.get().getRawQuery());
                assertEquals(404, get(URI.create(query.get("redirect_uri") + "favicon.ico")).statusCode());
                assertEquals(400, get(callback(query, "state=wrong&code=bad")).statusCode());
                assertEquals(400, get(callback(query, "state=" + query.get("state"))).statusCode());
                assertEquals(400, get(callback(query, "state=" + query.get("state") + "&code=a&code=b")).statusCode());
                var post = HttpRequest.newBuilder(URI.create(query.get("redirect_uri"))).POST(HttpRequest.BodyPublishers.noBody()).build();
                assertEquals(404, BROWSER.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
                assertFalse(operation.result().isDone());
                assertTrue(fixture.requests.isEmpty());
                assertEquals(200, get(callback(query, "state=" + query.get("state") + "&code=good")).statusCode());
                assertEquals(PROFILE_ID, operation.result().get(5, TimeUnit.SECONDS).uuid());
            }
        }
    }

    @Test
    void cancelBeforeCallbackClosesTheListenerAndDoesNoProviderWork() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<URI> authorize = new AtomicReference<>();
            var operation = fixture.client().beginLogin(authorize::set, Duration.ofSeconds(5));
            var query = MicrosoftAuth.parseQuery(authorize.get().getRawQuery());
            operation.close();
            operation.close();
            assertTrue(operation.result().isCancelled());
            assertListenerClosed(query);
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test
    void localhostCallbackAcceptsBothAddressFamiliesAndClosesBothOnCancel() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<URI> authorize = new AtomicReference<>();
            try (var operation = fixture.client().beginLogin(authorize::set, Duration.ofSeconds(5))) {
                var query = MicrosoftAuth.parseQuery(authorize.get().getRawQuery());
                URI localhost = URI.create(query.get("redirect_uri"));
                URI ipv4 = URI.create("http://127.0.0.1:" + localhost.getPort() + "/?state=invalid&code=ignored");
                assertEquals(400, get(ipv4).statusCode());
                if (ipv6LoopbackAvailable()) {
                    URI ipv6 = URI.create("http://[::1]:" + localhost.getPort() + "/?state=invalid&code=ignored");
                    assertEquals(400, get(ipv6).statusCode());
                    operation.close();
                    assertThrows(IOException.class, () -> get(ipv6));
                } else {
                    operation.close();
                }
                assertThrows(IOException.class, () -> get(ipv4));
                assertTrue(operation.result().isCancelled());
                assertTrue(fixture.requests.isEmpty());
            }
        }
    }

    @Test
    void validCallbacksFromEitherAddressFamilyUseTheSameAdvertisedLocalhostRedirect() throws Exception {
        for (String address : ipv6LoopbackAvailable() ? List.of("127.0.0.1", "[::1]") : List.of("127.0.0.1")) {
            try (Fixture fixture = new Fixture()) {
                AtomicReference<URI> authorize = new AtomicReference<>();
                try (var operation = fixture.client().beginLogin(authorize::set, Duration.ofSeconds(5))) {
                    var query = MicrosoftAuth.parseQuery(authorize.get().getRawQuery());
                    int port = URI.create(query.get("redirect_uri")).getPort();
                    URI callback = URI.create("http://" + address + ":" + port + "/?state=" + query.get("state") + "&code=valid");
                    assertEquals(200, get(callback).statusCode());
                    assertEquals(PROFILE_ID, operation.result().get(3, TimeUnit.SECONDS).uuid());
                    assertEquals(query.get("redirect_uri"), MicrosoftAuth.parseQuery(fixture.request("/token").body()).get("redirect_uri"));
                    assertThrows(IOException.class, () -> get(URI.create("http://127.0.0.1:" + port + "/")));
                    if (ipv6LoopbackAvailable()) {
                        assertThrows(IOException.class, () -> get(URI.create("http://[::1]:" + port + "/")));
                    }
                }
            }
        }
    }

    @Test
    void cancellingTheResultAlsoOwnsListenerCleanup() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<URI> authorize = new AtomicReference<>();
            try (var operation = fixture.client().beginLogin(authorize::set, Duration.ofSeconds(5))) {
                operation.result().cancel(false);
                assertListenerClosed(MicrosoftAuth.parseQuery(authorize.get().getRawQuery()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/token", "/xbox", "/xsts", "/minecraft", "/profile"})
    void cancelAfterCallbackInterruptsTheHttpChainAndCannotPublishSuccess(String phase) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.blockPath = phase;
            AtomicReference<URI> authorize = new AtomicReference<>();
            try (var operation = fixture.client().beginLogin(authorize::set, Duration.ofSeconds(5))) {
                var query = MicrosoftAuth.parseQuery(authorize.get().getRawQuery());
                get(callback(query, "state=" + query.get("state") + "&code=good"));
                assertTrue(fixture.tokenEntered.await(3, TimeUnit.SECONDS));
                operation.close();
                fixture.releaseToken.countDown();
                assertTrue(operation.result().isCancelled());
                assertListenerClosed(query);
                assertEquals(phase, fixture.requests.getLast().path());
            }
        }
    }

    @Test
    void duplicateCallbackCannotStartAnotherTokenExchange() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.blockPath = "/token";
            AtomicReference<URI> authorize = new AtomicReference<>();
            try (var operation = fixture.client().beginLogin(authorize::set, Duration.ofSeconds(5))) {
                var query = MicrosoftAuth.parseQuery(authorize.get().getRawQuery());
                URI uri = callback(query, "state=" + query.get("state") + "&code=good");
                assertEquals(200, get(uri).statusCode());
                assertTrue(fixture.tokenEntered.await(3, TimeUnit.SECONDS));
                assertThrows(IOException.class, () -> get(uri));
                fixture.releaseToken.countDown();
                operation.result().get(3, TimeUnit.SECONDS);
                assertEquals(1L, fixture.requests.stream().filter(request -> request.path().equals("/token")).count());
            }
        }
    }

    @Test
    void denialWithMatchingStateIsTerminalButProviderDescriptionIsNotExposed() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<URI> authorize = new AtomicReference<>();
            try (var operation = fixture.client().beginLogin(authorize::set, Duration.ofSeconds(5))) {
                var query = MicrosoftAuth.parseQuery(authorize.get().getRawQuery());
                get(callback(query, "state=" + query.get("state") + "&error=access_denied&error_description=synthetic-secret"));
                var failure = (AuthFailure) assertThrows(ExecutionException.class, () -> operation.result().get(3, TimeUnit.SECONDS)).getCause();
                assertFalse(failure.retryable());
                assertFalse(failure.toString().contains("synthetic-secret"));
                assertTrue(fixture.requests.isEmpty());
                assertListenerClosed(query);
            }
        }
    }

    @Test
    void deadlineClosesListenerEvenWithoutABrowserCallback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<URI> authorize = new AtomicReference<>();
            try (var operation = fixture.client().beginLogin(authorize::set, Duration.ofMillis(50))) {
                var failure = (AuthFailure) assertThrows(ExecutionException.class, () -> operation.result().get(3, TimeUnit.SECONDS)).getCause();
                assertTrue(failure.retryable());
                assertListenerClosed(MicrosoftAuth.parseQuery(authorize.get().getRawQuery()));
            }
        }
    }

    @Test
    void failedBrowserOpeningClosesListenerAndRedactsTheUnderlyingException() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<URI> authorize = new AtomicReference<>();
            try (var operation = fixture.client().beginLogin(uri -> {
                authorize.set(uri);
                throw new IllegalStateException("synthetic-secret");
            }, Duration.ofSeconds(5))) {
                var failure = assertThrows(ExecutionException.class, () -> operation.result().get(3, TimeUnit.SECONDS));
                assertFalse(failure.toString().contains("synthetic-secret"));
                assertListenerClosed(MicrosoftAuth.parseQuery(authorize.get().getRawQuery()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 408, 429, 500, 503})
    void httpErrorsAreClassifiedWithoutLeakingBodies(int status) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply("/token", status, "{\"error_description\":\"synthetic-secret\"}");
            AuthFailure error = assertThrows(AuthFailure.class, () -> fixture.client().refresh("refresh-secret"));
            assertEquals(status == 408 || status == 429 || status >= 500, error.retryable());
            assertFalse(error.toString().contains("synthetic-secret"));
            assertFalse(error.toString().contains("refresh-secret"));
            assertNull(error.getCause());
        }
    }

    @Test
    void invalidGrantRequiresInteractiveSignIn() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply("/token", 400, "{\"error\":\"invalid_grant\",\"error_description\":\"synthetic-secret\"}");
            AuthFailure error = assertThrows(AuthFailure.class, () -> fixture.client().refresh("refresh-secret"));
            assertFalse(error.retryable());
            assertTrue(error.getMessage().contains("Add the Microsoft account"));
        }
    }

    @Test
    void redirectsCannotForwardCredentialsToAnotherEndpoint() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply("/token", 307, "{}");
            assertFalse(assertThrows(AuthFailure.class, () -> fixture.client().refresh("refresh-secret")).retryable());
            assertEquals(1, fixture.requests.size());
        }
    }

    @Test
    void queryParserRejectsMalformedOrAmbiguousInput() {
        assertThrows(IllegalArgumentException.class, () -> MicrosoftAuth.parseQuery("state=%ZZ"));
        assertThrows(IllegalArgumentException.class, () -> MicrosoftAuth.parseQuery("state=a&state=b"));
        assertThrows(IllegalArgumentException.class, () -> MicrosoftAuth.parseQuery("state"));
        assertEquals(Map.of("code", "a+b /"), MicrosoftAuth.parseQuery("code=a%2Bb+%2F"));
    }

    @Test
    void xstsNon2xxErrorRetainsSafeActionableMeaning() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply("/xsts", 401, "{\"XErr\":2148916233,\"Message\":\"synthetic-secret\"}");
            AuthFailure error = assertThrows(AuthFailure.class, () -> fixture.client().refresh("refresh-secret"));
            assertFalse(error.retryable());
            assertTrue(error.getMessage().contains("Xbox profile"));
            assertFalse(error.toString().contains("synthetic-secret"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/xbox", "/xsts", "/minecraft", "/profile"})
    void downstreamServicesClassifyFailuresWithoutReturningPartialCredentials(String phase) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply(phase, 503, "{\"error_description\":\"synthetic-secret\"}");
            AuthFailure error = assertThrows(AuthFailure.class, () -> fixture.client().refresh("old-refresh"));
            assertTrue(error.retryable());
            assertFalse(error.toString().contains("synthetic-secret"));
            assertEquals(phase, fixture.requests.getLast().path());
        }
    }

    @Test
    void xstsErrorInASuccessResponseIsStillAnActionableFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply("/xsts", 200, "{\"XErr\":2148916238}");
            AuthFailure error = assertThrows(AuthFailure.class, () -> fixture.client().refresh("old-refresh"));
            assertFalse(error.retryable());
            assertTrue(error.getMessage().contains("parental-consent"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json synthetic-secret", "[]", "null", "{}", "{\"access_token\":null}", "{\"access_token\":42}"})
    void malformedTokenResponsesDoNotLeakOrContinue(String body) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply("/token", 200, body);
            AuthFailure error = assertThrows(AuthFailure.class, () -> fixture.client().refresh("refresh-secret"));
            assertTrue(error.retryable());
            assertFalse(error.toString().contains("synthetic-secret"));
            assertEquals(1, fixture.requests.size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"Token\":\"xsts-token\"}", "{\"Token\":\"xsts-token\",\"DisplayClaims\":{\"xui\":[]}}"})
    void incompleteXboxClaimsFailSafely(String body) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply("/xsts", 200, body);
            assertThrows(AuthFailure.class, () -> fixture.client().refresh("refresh-secret"));
            assertEquals(3, fixture.requests.size());
        }
    }

    @Test
    void incompleteOrInvalidProfileFailsSafely() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply("/profile", 200, "{\"name\":\"FixturePlayer\",\"id\":\"synthetic-secret\"}");
            AuthFailure error = assertThrows(AuthFailure.class, () -> fixture.client().refresh("refresh-secret"));
            assertFalse(error.toString().contains("synthetic-secret"));
        }
    }

    @Test
    void httpDeadlineIsBoundedAndRetryable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.blockPath = "/token";
            var http = new HttpJson(BROWSER, Duration.ofMillis(50));
            var client = new MicrosoftAuth.Client("fixture-client", fixture.endpoints(), http);
            assertTrue(assertThrows(AuthFailure.class, () -> client.refresh("refresh-secret")).retryable());
        }
    }

    @Test
    void unconfiguredClientAndMissingRefreshFailBeforeNetwork() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertFalse(assertThrows(AuthFailure.class, () -> new MicrosoftAuth.Client("", fixture.endpoints(), new HttpJson())).retryable());
            assertFalse(assertThrows(AuthFailure.class, () -> fixture.client().refresh(" ")).retryable());
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test
    void pkceMatchesTheRfc7636KnownVector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", MicrosoftAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    private static URI callback(Map<String, String> query, String params) {
        return URI.create(query.get("redirect_uri") + "?" + params);
    }

    private static boolean ipv6LoopbackAvailable() {
        try {
            HttpServer probe = HttpServer.create(new InetSocketAddress("::1", 0), 0);
            probe.stop(0);
            return true;
        } catch (IOException | UnsupportedOperationException unavailable) {
            return false;
        }
    }

    private static HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
        return BROWSER.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertListenerClosed(Map<String, String> query) {
        assertThrows(IOException.class, () -> get(callback(query, "state=" + query.get("state") + "&code=late")));
    }

    private record Request(String path, String body, String authorization) {
    }

    private record Response(int status, String body) {
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final Map<String, Response> responses = new ConcurrentHashMap<>();
        final CountDownLatch tokenEntered = new CountDownLatch(1);
        final CountDownLatch releaseToken = new CountDownLatch(1);
        volatile String blockPath;

        Fixture() throws IOException {
            reply("/token", 200, "{\"access_token\":\"microsoft-access\",\"refresh_token\":\"rotated-refresh\"}");
            reply("/xbox", 200, "{\"Token\":\"xbox-token\"}");
            reply("/xsts", 200, "{\"Token\":\"xsts-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"user-hash\"}]}}");
            reply("/minecraft", 200, "{\"access_token\":\"minecraft-access\"}");
            reply("/profile", 200, "{\"name\":\"FixturePlayer\",\"id\":\"11111111222233334444555555555555\"}");
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        MicrosoftAuth.Endpoints endpoints() {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            return new MicrosoftAuth.Endpoints(base + "/authorize", base + "/token", base + "/xbox", base + "/xsts", base + "/minecraft", base + "/profile");
        }

        MicrosoftAuth.Client client() throws AuthFailure {
            return new MicrosoftAuth.Client("fixture-client", endpoints(), new HttpJson());
        }

        void reply(String path, int status, String body) {
            responses.put(path, new Response(status, body));
        }

        Request request(String path) {
            return requests.stream().filter(request -> request.path().equals(path)).findFirst().orElseThrow();
        }

        void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                requests.add(new Request(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), exchange.getRequestHeaders().getFirst("Authorization")));
                if (path.equals(blockPath)) {
                    tokenEntered.countDown();
                    try {
                        releaseToken.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                Response response = responses.getOrDefault(path, new Response(404, "{}"));
                byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (response.status() >= 300 && response.status() < 400) {
                    exchange.getResponseHeaders().set("Location", "/unexpected");
                }
                exchange.sendResponseHeaders(response.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        @Override
        public void close() {
            releaseToken.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
