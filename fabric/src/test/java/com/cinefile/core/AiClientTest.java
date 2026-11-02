package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiClientTest {
    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<String> auths = new ArrayList<>();
    private final List<JsonObject> bodies = new ArrayList<>();
    private final ConcurrentLinkedQueue<Object[]> responses = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            synchronized (paths) {
                paths.add(ex.getRequestURI().getPath());
                auths.add(ex.getRequestHeaders().getFirst("Authorization"));
                bodies.add(JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            }
            Object[] r = responses.poll();
            if (r == null) r = new Object[] {500, "{}"};
            byte[] b = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders((Integer) r[0], b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String chat(String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.add("message", msg);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject o = new JsonObject();
        o.add("choices", choices);
        return o.toString();
    }

    private static String chatWithUsage(String content, int in, int out) {
        JsonObject o = JsonParser.parseString(chat(content)).getAsJsonObject();
        JsonObject u = new JsonObject();
        u.addProperty("prompt_tokens", in);
        u.addProperty("completion_tokens", out);
        o.add("usage", u);
        return o.toString();
    }

    private static <T> T get(java.util.concurrent.CompletableFuture<T> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    private static List<Candidate> cands(int n) {
        List<Candidate> c = new ArrayList<>();
        for (int i = 0; i < n; i++) c.add(new Candidate(i * 100, i * 100 + 200, 1.0 + (i % 7)));
        return c;
    }

    private AiClient client(boolean think) {
        return new AiClient("sk-test", url(), "m1", 10.0, think);
    }

    @Test
    void blankKeySendsNothing() throws Exception {
        AiClient c = new AiClient("", url(), "m", 10, false);
        assertFalse(c.enabled());
        assertTrue(get(c.planClips(cands(5), "p", 2, 5)).isEmpty());
        assertTrue(paths.isEmpty());
    }

    @Test
    void remotePlainHttpRefused() throws Exception {
        AiClient c = new AiClient("k", "http://example.com", "m", 10, false);
        assertFalse(c.enabled());
        assertTrue(get(c.planClips(cands(5), "p", 2, 5)).isEmpty());
        assertNotNull(c.lastError());
    }

    @Test
    void localhostHttpAllowed() throws Exception {
        AiClient c = new AiClient("k", url() + "/", "m", 10, false);
        assertTrue(c.enabled());
        responses.add(new Object[] {200, chat("{\"indices\":[0]}")});
        get(c.planClips(cands(5), "p", 2, 5));
        assertEquals(List.of("/chat/completions"), paths);
    }

    @Test
    void badUrlRefused() {
        assertFalse(new AiClient("k", "not a url", "m", 10, false).enabled());
    }

    @Test
    void planClipsParsesAndFilters() throws Exception {
        AiClient c = client(false);
        responses.add(new Object[] {200, chatWithUsage("Sure! {\"indices\": [0, 3, 9999, -4]} hope that helps", 1000, 50)});
        Optional<List<Integer>> r = get(c.planClips(cands(5), "proj", 2, 5));
        assertEquals(List.of(0, 3), r.orElseThrow());
        assertEquals("Bearer sk-test", auths.get(0));
        assertEquals("m1", bodies.get(0).get("model").getAsString());
        assertEquals(1000, c.usage().in());
        assertEquals(50, c.usage().out());
    }

    @Test
    void planPoolCoversTimeline() throws Exception {
        // 400 candidates; the best-scoring ones all sit at the start
        List<Candidate> cs = new ArrayList<>();
        for (int i = 0; i < 400; i++) cs.add(new Candidate(i * 10, i * 10 + 100, i < 200 ? 100 - i * 0.1 : 1.0));
        responses.add(new Object[] {200, chat("{\"indices\":[0]}")});
        get(client(false).planClips(cs, "p", 10, 5));
        String user = bodies.get(0).getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        JsonArray windows = JsonParser.parseString(user).getAsJsonObject().getAsJsonArray("windows");
        assertTrue(windows.size() <= 120);
        double maxStart = 0;
        for (JsonElement w : windows) maxStart = Math.max(maxStart, w.getAsJsonObject().get("start_s").getAsDouble());
        // last candidate starts at 3990 ticks = 199.5 s; the pool must reach the late slices
        assertTrue(maxStart > 150, "max start_s " + maxStart);
    }

    @Test
    void serverErrorGivesEmpty() throws Exception {
        AiClient c = client(false);
        responses.add(new Object[] {500, "oops"});
        assertTrue(get(c.planClips(cands(5), "p", 2, 5)).isEmpty());
        assertEquals("HTTP 500", c.lastError());
    }

    @Test
    void badContentGivesEmpty() throws Exception {
        responses.add(new Object[] {200, "{\"choices\":[{\"message\":{\"content\":\"nope\"}}]}"});
        assertTrue(get(client(false).planClips(cands(5), "p", 2, 5)).isEmpty());
    }

    @Test
    void overBudgetSendsNothing() throws Exception {
        AiClient c = new AiClient("k", url(), "m", 0, false);
        assertTrue(get(c.planClips(cands(5), "p", 2, 5)).isEmpty());
        assertTrue(paths.isEmpty());
        assertEquals("Over budget", c.lastError());
    }

    @Test
    void thinkControlsReasoningEffort() throws Exception {
        responses.add(new Object[] {200, chat("{\"indices\":[0]}")});
        responses.add(new Object[] {200, chat("{\"indices\":[0]}")});
        get(client(false).planClips(cands(5), "p", 2, 5));
        get(client(true).planClips(cands(5), "p", 2, 5));
        assertTrue(bodies.get(0).has("reasoning_effort"));
        assertFalse(bodies.get(1).has("reasoning_effort"));
    }

    @Test
    void retriesWithoutOptionalFieldsOn400() throws Exception {
        responses.add(new Object[] {400, "bad"});
        responses.add(new Object[] {200, chat("{\"indices\":[1]}")});
        Optional<List<Integer>> r = get(client(false).planClips(cands(5), "p", 2, 5));
        assertEquals(List.of(1), r.orElseThrow());
        assertEquals(2, bodies.size());
        assertTrue(bodies.get(0).has("temperature"));
        assertFalse(bodies.get(1).has("temperature"));
        assertFalse(bodies.get(1).has("reasoning_effort"));
    }

    @Test
    void reviewSendsDataUrls() throws Exception {
        responses.add(new Object[] {200, chat("{\"score\": 7}")});
        List<byte[]> frames = List.of(new byte[] {1, 2}, new byte[] {3}, new byte[] {4, 5, 6});
        assertEquals(7.0, get(client(false).reviewClip(frames, "proj")).orElseThrow());
        JsonArray parts = bodies.get(0).getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content");
        int images = 0;
        for (JsonElement e : parts) {
            JsonObject p = e.getAsJsonObject();
            if (!p.get("type").getAsString().equals("image_url")) continue;
            images++;
            JsonObject iu = p.getAsJsonObject("image_url");
            assertEquals("low", iu.get("detail").getAsString());
            assertTrue(iu.get("url").getAsString().startsWith("data:image/png;base64,"));
        }
        assertEquals(3, images);
        assertEquals("AQI=", parts.get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString().substring("data:image/png;base64,".length()));
    }

    @Test
    void reviewScoreClampedOrEmpty() throws Exception {
        AiClient c = client(false);
        responses.add(new Object[] {200, chat("{\"score\": 42}")});
        responses.add(new Object[] {200, chat("{\"score\": \"x\"}")});
        List<byte[]> f = List.of(new byte[] {1});
        assertEquals(10.0, get(c.reviewClip(f, "p")).orElseThrow());
        assertTrue(get(c.reviewClip(f, "p")).isEmpty());
    }

    @Test
    void lastErrorNeverContainsKey() throws Exception {
        AiClient c = new AiClient("sk-secret", url(), "m", 10, false);
        responses.add(new Object[] {401, "bad key sk-secret"});
        get(c.planClips(cands(5), "p", 2, 5));
        assertEquals("HTTP 401", c.lastError());
        assertFalse(c.lastError().contains("sk-secret"));
        AiClient bad = new AiClient("sk-secret", "http://sk-secret.example.com", "m", 10, false);
        get(bad.planClips(cands(5), "p", 2, 5));
        assertFalse(bad.lastError().contains("sk-secret"));
    }

    @Test
    void usageAccumulates() throws Exception {
        AiClient c = client(false);
        responses.add(new Object[] {200, chatWithUsage("{\"indices\":[0]}", 1000, 100)});
        responses.add(new Object[] {200, chatWithUsage("{\"indices\":[0]}", 2000, 200)});
        get(c.planClips(cands(5), "p", 2, 5));
        get(c.planClips(cands(5), "p", 2, 5));
        assertEquals(3000, c.usage().in());
        assertEquals(300, c.usage().out());
        assertEquals(3000 / 1e6 * 0.15 + 300 / 1e6 * 0.60, c.usage().estimateUsd(), 1e-12);
    }
}
