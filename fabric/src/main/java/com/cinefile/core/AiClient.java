package com.cinefile.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** OpenAI-compatible chat client (port of the DeepSeek parts of cinefile/ai.py). */
public final class AiClient implements ShotAi {
    private static final Logger LOGGER = Logger.getLogger("cinefile");
    private static final int IMAGE_TOKENS = 1024;
    private static final String REVIEW_SYSTEM = "You judge Minecraft cinematic camera shots from screenshots of the real game. "
        + "The frames are the start, middle and end of one shot. Score it 0-10: the player is visible and at the center "
        + "or on rule-of-thirds points in most frames; the camera is not inside or behind blocks; the composition reads "
        + "clearly with depth. Reply with ONLY JSON: {\"score\": number}.";

    private final String apiKey;
    private final String base;
    private final String model;
    private final double maxUsd;
    private final boolean think;
    private final AiUsage usage = new AiUsage();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile String lastError;

    public AiClient(String apiKey, String baseUrl, String model, double maxUsd, boolean think) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.base = allowed(baseUrl) ? baseUrl.trim().replaceAll("/+$", "") : null;
        this.model = model;
        this.maxUsd = maxUsd;
        this.think = think;
    }

    private static boolean allowed(String url) {
        try {
            URI u = new URI(url.trim());
            String host = u.getHost();
            if (host == null) return false;
            if ("https".equalsIgnoreCase(u.getScheme())) return true;
            return "http".equalsIgnoreCase(u.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]").contains(host.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    public boolean enabled() { return !apiKey.isBlank() && base != null; }

    public AiUsage usage() { return usage; }

    public String lastError() { return lastError; }

    private void fail(String text) {
        lastError = text;
        LOGGER.warning("AI request failed: " + text);
    }

    private static JsonObject msg(String role, Object content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        if (content instanceof String s) m.addProperty("content", s);
        else m.add("content", (JsonElement) content);
        return m;
    }

    private CompletableFuture<Optional<String>> chat(JsonArray messages, int estIn, int estOut) {
        if (!enabled()) {
            fail(base == null ? "Base URL not allowed" : "No API key");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (usage.estimateUsd() + estIn / 1e6 * AiUsage.PRICE_IN + estOut / 1e6 * AiUsage.PRICE_OUT > maxUsd) {
            fail("Over budget");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return send(messages, estIn, estOut, true).handle((r, e) -> {
            if (e != null) {
                fail("Network error");
                return Optional.<String>empty();
            }
            return r;
        });
    }

    private CompletableFuture<Optional<String>> send(JsonArray messages, int estIn, int estOut, boolean optionalFields) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        if (optionalFields) {
            body.addProperty("temperature", 0.3);
            if (!think) body.addProperty("reasoning_effort", "low");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenCompose(resp -> {
            int code = resp.statusCode();
            if (code == 400 && optionalFields) return send(messages, estIn, estOut, false);
            if (code < 200 || code >= 300) {
                fail("HTTP " + code);
                return CompletableFuture.completedFuture(Optional.<String>empty());
            }
            try {
                JsonObject data = JsonParser.parseString(resp.body()).getAsJsonObject();
                String content = data.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
                JsonObject u = data.has("usage") && data.get("usage").isJsonObject() ? data.getAsJsonObject("usage") : new JsonObject();
                usage.add(u.has("prompt_tokens") ? u.get("prompt_tokens").getAsInt() : estIn,
                    u.has("completion_tokens") ? u.get("completion_tokens").getAsInt() : estOut);
                return CompletableFuture.completedFuture(Optional.of(content));
            } catch (Exception e) {
                fail("Bad response");
                return CompletableFuture.completedFuture(Optional.<String>empty());
            }
        });
    }

    private JsonObject extractJson(String content) {
        try {
            int s = content.indexOf('{'), e = content.lastIndexOf('}');
            if (s < 0 || e < 0) throw new IllegalArgumentException();
            return JsonParser.parseString(content.substring(s, e + 1)).getAsJsonObject();
        } catch (Exception ex) {
            fail("Bad response");
            return null;
        }
    }

    public CompletableFuture<Optional<List<Integer>>> planClips(List<Candidate> ranked, String project, int targetClips, double clipSeconds) {
        if (targetClips <= 0 || ranked.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
        // A global top 120 can come from one stretch of the replay, so take the best of each time slice.
        int n = Math.min(targetClips, 120);
        int per = Math.max(1, 120 / n);
        TreeSet<Integer> sorted = new TreeSet<>();
        for (List<Integer> sl : Candidates.slices(ranked, n)) sorted.addAll(sl.subList(0, Math.min(per, sl.size())));
        List<Integer> pool = new ArrayList<>(sorted).subList(0, Math.min(120, sorted.size()));
        Set<Integer> poolSet = new TreeSet<>(pool);
        JsonArray windows = new JsonArray();
        for (int i : pool) {
            Candidate c = ranked.get(i);
            JsonObject w = new JsonObject();
            w.addProperty("index", i);
            w.addProperty("start_s", Math.round(c.start() / 20.0 * 10) / 10.0);
            w.addProperty("end_s", Math.round(c.end() / 20.0 * 10) / 10.0);
            w.addProperty("score", Math.round(c.score() * 100) / 100.0);
            windows.add(w);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("project", project == null || project.isEmpty() ? "minecraft session" : project);
        payload.addProperty("clip_seconds", clipSeconds);
        payload.addProperty("need_clips", targetClips);
        payload.add("windows", windows);
        JsonArray messages = new JsonArray();
        messages.add(msg("system", "You pick cinematic Minecraft Flashback clip windows. "
            + "Reply with ONLY JSON: {\"indices\": [int, ...]}. "
            + "Cover representative phases of the project; avoid clustering; never pick overlapping windows."));
        messages.add(msg("user", payload.toString()));
        return chat(messages, 800 + pool.size() * 25, 200 + targetClips * 3).thenApply(content -> {
            if (content.isEmpty()) return Optional.<List<Integer>>empty();
            JsonObject parsed = extractJson(content.get());
            if (parsed == null) return Optional.<List<Integer>>empty();
            List<Integer> out = new ArrayList<>();
            try {
                for (JsonElement e : parsed.getAsJsonArray("indices")) {
                    int i = e.getAsInt();
                    if (poolSet.contains(i)) out.add(i);
                }
            } catch (Exception e) {
                fail("Bad response");
                return Optional.<List<Integer>>empty();
            }
            return out.isEmpty() ? Optional.<List<Integer>>empty() : Optional.of(out);
        });
    }

    public CompletableFuture<Optional<Double>> reviewClip(List<byte[]> pngFrames, String project) {
        JsonArray parts = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", "Project: " + (project == null || project.isEmpty() ? "minecraft session" : project));
        parts.add(text);
        for (byte[] png : pngFrames) {
            JsonObject iu = new JsonObject();
            iu.addProperty("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            iu.addProperty("detail", "low");
            JsonObject part = new JsonObject();
            part.addProperty("type", "image_url");
            part.add("image_url", iu);
            parts.add(part);
        }
        JsonArray messages = new JsonArray();
        messages.add(msg("system", REVIEW_SYSTEM));
        messages.add(msg("user", parts));
        return chat(messages, 600 + IMAGE_TOKENS * pngFrames.size(), 320).thenApply(content -> {
            if (content.isEmpty()) return Optional.<Double>empty();
            JsonObject parsed = extractJson(content.get());
            if (parsed == null) return Optional.<Double>empty();
            try {
                double s = parsed.get("score").getAsDouble();
                if (Double.isNaN(s)) throw new NumberFormatException();
                return Optional.of(Math.max(0.0, Math.min(10.0, s)));
            } catch (Exception e) {
                fail("Bad response");
                return Optional.<Double>empty();
            }
        });
    }
}
