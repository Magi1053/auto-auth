package com.skcraft.autoauth.runtimetest;

import com.skcraft.autoauth.auth.MicrosoftAuth;
import com.skcraft.autoauth.auth.HttpJson;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Consumer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Uses the real OAuth listener and HTTP chain, but only loopback providers and a captured browser URI. */
public final class RuntimeAuthFixture {
    private static HttpServer provider;
    private static volatile URI authorization;
    public static volatile MicrosoftAuth.LoginOperation operation;

    private RuntimeAuthFixture() { }

    public static synchronized MicrosoftAuth.LoginOperation begin() throws IOException {
        if (provider == null) {
            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            provider.createContext("/", exchange -> {
                String body = switch (exchange.getRequestURI().getPath()) {
                    case "/token" -> "{\"access_token\":\"synthetic-ms-access\",\"refresh_token\":\"synthetic-added-refresh\"}";
                    case "/xbox" -> "{\"Token\":\"synthetic-xbox\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"synthetic-uhs\"}]}}";
                    case "/xsts" -> "{\"Token\":\"synthetic-xsts\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"synthetic-uhs\"}]}}";
                    case "/minecraft" -> "{\"access_token\":\"synthetic-added-access\"}";
                    case "/profile" -> "{\"id\":\"33333333333333333333333333333333\",\"name\":\"AddedAccount\"}";
                    default -> null;
                };
                if (body == null) {
                    exchange.sendResponseHeaders(500, -1);
                } else {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) { out.write(bytes); }
                }
                exchange.close();
            });
            provider.start();
        }
        String base = "http://127.0.0.1:" + provider.getAddress().getPort();
        try {
            Class<?> endpointsType = Class.forName(MicrosoftAuth.class.getName() + "$Endpoints");
            var endpointsConstructor = endpointsType.getDeclaredConstructor(String.class, String.class, String.class,
                    String.class, String.class, String.class);
            endpointsConstructor.setAccessible(true);
            Object endpoints = endpointsConstructor.newInstance(base + "/authorize", base + "/token", base + "/xbox",
                    base + "/xsts", base + "/minecraft", base + "/profile");
            Class<?> clientType = Class.forName(MicrosoftAuth.class.getName() + "$Client");
            var clientConstructor = clientType.getDeclaredConstructor(String.class, endpointsType, HttpJson.class);
            clientConstructor.setAccessible(true);
            Object client = clientConstructor.newInstance("synthetic-public-client", endpoints, new HttpJson());
            var begin = clientType.getDeclaredMethod("beginLogin", Consumer.class, Duration.class);
            begin.setAccessible(true);
            operation = (MicrosoftAuth.LoginOperation) begin.invoke(client, (Consumer<URI>) uri -> authorization = uri,
                    Duration.ofSeconds(30));
        } catch (ReflectiveOperationException error) {
            throw new IOException("Could not create loopback authentication fixture", error);
        }
        return operation;
    }

    public static URI callbackUri() {
        Map<String, String> params = query(authorization.getRawQuery());
        return URI.create(params.get("redirect_uri") + "?state=" + encode(params.get("state")) + "&code=synthetic-code");
    }

    public static CompletableFuture<Void> completeLogin() {
        return HttpClient.newHttpClient().sendAsync(HttpRequest.newBuilder(callbackUri()).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).thenAccept(response -> {
                    if (response.statusCode() != 200) throw new AssertionError("Fixture callback rejected");
                });
    }

    public static synchronized void close() {
        if (operation != null) operation.close();
        if (provider != null) provider.stop(0);
        provider = null;
    }

    private static Map<String, String> query(String query) {
        Map<String, String> values = new HashMap<>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
