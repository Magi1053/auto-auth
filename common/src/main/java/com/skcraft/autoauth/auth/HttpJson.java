package com.skcraft.autoauth.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/** Bounded transport; provider bodies and credentials never enter exceptions. */
public final class HttpJson {
    private static final Gson GSON = new Gson();
    private final HttpClient client;
    private final Duration timeout;

    public HttpJson() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Duration.ofSeconds(45));
    }

    HttpJson(HttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    public JsonObject postJson(String url, Object body) throws IOException, InterruptedException {
        return postJson(url, body, Map.of());
    }

    public JsonObject postJson(String url, Object body, Map<String, String> headers) throws IOException, InterruptedException {
        String json = body instanceof String s ? s : GSON.toJson(body);
        HttpRequest.Builder builder = request(url).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return send(builder.build());
    }

    public JsonObject postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
        StringJoiner joiner = new StringJoiner("&");
        form.forEach((key, value) -> joiner.add(encode(key) + "=" + encode(value)));
        return send(request(url).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(joiner.toString(), StandardCharsets.UTF_8)).build());
    }

    public JsonObject getJson(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = request(url).GET();
        headers.forEach(builder::header);
        return send(builder.build());
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Accept", "application/json");
    }

    private JsonObject send(HttpRequest request) throws IOException, InterruptedException {
        String service = switch (request.uri().getHost()) {
            case "login.microsoftonline.com" -> "Microsoft sign-in";
            case "user.auth.xboxlive.com" -> "Xbox sign-in";
            case "xsts.auth.xboxlive.com" -> "Xbox authorization";
            case "api.minecraftservices.com" -> request.uri().getPath().equals("/minecraft/profile")
                    ? "Minecraft profile lookup" : "Minecraft sign-in";
            default -> "The sign-in service";
        };
        final HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new AuthFailure(service + " is unreachable. Try again later.", true);
        }
        JsonObject json = null;
        try {
            var parsed = JsonParser.parseString(response.body() == null ? "" : response.body());
            if (parsed.isJsonObject()) {
                json = parsed.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
            // Parser errors may contain provider response snippets; never expose them.
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            if (json != null && "invalid_client".equals(string(json, "error"))) {
                String description = string(json, "error_description");
                var code = java.util.regex.Pattern.compile("AADSTS[0-9]+").matcher(description == null ? "" : description);
                String reference = code.find() ? " (" + code.group() + ")" : "";
                throw new AuthFailure(service + " rejected the application's registration" + reference
                        + ". Configure this app as a Mobile and desktop public client with the localhost redirect.", false);
            }
            if (json != null && "invalid_grant".equals(string(json, "error"))) {
                throw new AuthFailure("This saved login has expired or was revoked. Add the Microsoft account again.", false);
            }
            if (json != null && json.has("XErr")) {
                throw xboxFailure(string(json, "XErr"));
            }
            boolean retry = status == 408 || status == 429 || status >= 500;
            throw new AuthFailure(retry ? service + " is temporarily unavailable. Try again later."
                    : service + " rejected this request (HTTP " + status + "). Sign in again or check account access.", retry);
        }
        if (json == null) {
            throw new AuthFailure(service + " returned an invalid response. Try again later.", true);
        }
        return json;
    }

    static AuthFailure xboxFailure(String code) {
        return switch (code == null ? "" : code) {
            case "2148916233" -> new AuthFailure("This Microsoft account needs an Xbox profile. Create one and sign in again.", false);
            case "2148916235" -> new AuthFailure("Xbox Live is unavailable for this account's country or region.", false);
            case "2148916236", "2148916237", "2148916238" -> new AuthFailure("This account needs family, age or parental-consent settings updated before signing in.", false);
            default -> new AuthFailure("Xbox could not authorize this account. Check account access and sign in again.", false);
        };
    }

    private static String string(JsonObject json, String key) {
        try {
            return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
