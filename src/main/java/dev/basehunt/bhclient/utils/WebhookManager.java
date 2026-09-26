package dev.basehunt.bhclient.utils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.basehunt.bhclient.BaseHuntingAddon;
import meteordevelopment.meteorclient.utils.network.Http;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Async, rate limited Discord webhook sender.
 *
 * <p>Minecraft's tick loop must never block on network IO, so every message is queued and drained by a
 * single daemon thread. One thread also keeps us inside Discord's per-webhook rate limit (5 requests
 * per 2 seconds) without having to coordinate across callers.
 */
public class WebhookManager {
    private static final WebhookManager INSTANCE = new WebhookManager();

    /** Discord allows 5 requests per 2s per webhook; 450ms keeps us comfortably under that. */
    private static final long MIN_SEND_INTERVAL_MS = 450;
    private static final int MAX_QUEUE_SIZE = 500;
    private static final long DUPLICATE_WINDOW_MS = 3000;

    private static final Gson GSON = new Gson();

    private final BlockingQueue<Payload> queue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private final Map<String, Long> recentMessages = new ConcurrentHashMap<>();

    private volatile boolean running;
    private Thread thread;

    private WebhookManager() {
    }

    public static WebhookManager get() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (running) return;

        running = true;
        thread = new Thread(this::run, "BaseHunting-Webhooks");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * @return true when the message was accepted into the queue.
     */
    public boolean enqueue(String url, String username, String content, List<Embed> embeds) {
        if (url == null || url.isBlank()) return false;

        // Collapse identical messages fired in the same tick burst (e.g. a chunk being re-sent). The key
        // must include the embed body, otherwise every embed would share one key and distinct alerts
        // arriving within the window would be dropped.
        String key = url + '\u0000' + content + '\u0000' + embedFingerprint(embeds);
        long now = System.currentTimeMillis();
        recentMessages.entrySet().removeIf(e -> now - e.getValue() > DUPLICATE_WINDOW_MS);
        if (recentMessages.putIfAbsent(key, now) != null) return false;

        JsonObject body = new JsonObject();
        if (username != null && !username.isBlank()) body.addProperty("username", username);
        if (content != null && !content.isBlank()) body.addProperty("content", content);

        if (embeds != null && !embeds.isEmpty()) {
            JsonArray array = new JsonArray();
            for (Embed embed : embeds) array.add(embed.toJson());
            body.add("embeds", array);
        }

        Payload payload = new Payload(url, GSON.toJson(body));
        if (!queue.offer(payload)) {
            BaseHuntingAddon.LOG.warn("Webhook queue is full, dropping message.");
            return false;
        }

        return true;
    }

    private static String embedFingerprint(List<Embed> embeds) {
        if (embeds == null || embeds.isEmpty()) return "";

        StringBuilder builder = new StringBuilder();
        for (Embed embed : embeds) builder.append(embed.fingerprint()).append('\u0001');
        return builder.toString();
    }

    public boolean enqueue(String url, String username, String content) {
        return enqueue(url, username, content, null);
    }

    public boolean enqueueEmbed(String url, String username, Embed embed) {
        return enqueueEmbed(url, username, embed, null);
    }

    public boolean enqueueEmbed(String url, String username, Embed embed, String content) {
        List<Embed> embeds = new ArrayList<>(1);
        embeds.add(embed);
        return enqueue(url, username, content, embeds);
    }

    private void run() {
        while (running) {
            try {
                Payload payload = queue.take();
                long started = System.currentTimeMillis();

                send(payload);
                trimRecentMessages();

                long elapsed = System.currentTimeMillis() - started;
                if (elapsed < MIN_SEND_INTERVAL_MS) Thread.sleep(MIN_SEND_INTERVAL_MS - elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                BaseHuntingAddon.LOG.error("Error while sending webhook", e);
            }
        }
    }

    private void trimRecentMessages() {
        long now = System.currentTimeMillis();
        recentMessages.entrySet().removeIf(e -> now - e.getValue() > DUPLICATE_WINDOW_MS);
    }

    private void send(Payload payload) {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpResponse<String> response = Http.post(payload.url())
                .bodyJson(payload.body())
                .exceptionHandler(e -> BaseHuntingAddon.LOG.warn("Webhook request failed: {}", e.getMessage()))
                .sendStringResponse();

            int status = response.statusCode();
            if (status == Http.SUCCESS || status == 204) return;

            if (status == 429) {
                // Discord tells us how long to back off for; honour it rather than guessing.
                double retryAfter = response.headers().firstValue("Retry-After")
                    .map(v -> {
                        try {
                            return Double.parseDouble(v);
                        } catch (NumberFormatException e) {
                            return 1.0;
                        }
                    })
                    .orElse(1.0);

                try {
                    Thread.sleep((long) (retryAfter * 1000) + 100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            if (status == Http.NOT_FOUND || status == Http.UNAUTHORIZED || status == Http.FORBIDDEN || status == Http.BAD_REQUEST) {
                BaseHuntingAddon.LOG.warn("Webhook rejected with status {}, check the URL.", status);
                return;
            }

            return;
        }
    }

    private record Payload(String url, String body) {
    }

    public static class Embed {
        private String title;
        private String description;
        private Integer color;
        private String footer;
        private String thumbnailUrl;
        private final List<Field> fields = new ArrayList<>();

        public Embed title(String title) {
            this.title = title;
            return this;
        }

        public Embed description(String description) {
            this.description = description;
            return this;
        }

        public Embed color(int color) {
            this.color = color;
            return this;
        }

        public Embed footer(String footer) {
            this.footer = footer;
            return this;
        }

        public Embed thumbnail(String url) {
            this.thumbnailUrl = url;
            return this;
        }

        public Embed field(String name, String value, boolean inline) {
            fields.add(new Field(name, value, inline));
            return this;
        }

        public Embed field(String name, String value) {
            return field(name, value, true);
        }

        /** Stable identity used for duplicate suppression; covers every field that reaches Discord. */
        private String fingerprint() {
            StringBuilder builder = new StringBuilder();
            builder.append(title).append('\u0001')
                .append(description).append('\u0001')
                .append(color).append('\u0001');

            for (Field field : fields) {
                builder.append(field.name()).append('=').append(field.value()).append('\u0001');
            }

            return builder.toString();
        }

        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            if (title != null) json.addProperty("title", title);
            if (description != null) json.addProperty("description", description);
            if (color != null) json.addProperty("color", color);
            if (footer != null) json.add("footer", footerObject(footer));
            if (thumbnailUrl != null) json.add("thumbnail", urlObject(thumbnailUrl));

            if (!fields.isEmpty()) {
                JsonArray array = new JsonArray();
                for (Field field : fields) {
                    JsonObject fieldJson = new JsonObject();
                    fieldJson.addProperty("name", field.name());
                    fieldJson.addProperty("value", field.value());
                    fieldJson.addProperty("inline", field.inline());
                    array.add(fieldJson);
                }
                json.add("fields", array);
            }

            return json;
        }

        private static JsonObject footerObject(String text) {
            JsonObject json = new JsonObject();
            json.addProperty("text", text);
            return json;
        }

        private static JsonObject urlObject(String url) {
            JsonObject json = new JsonObject();
            json.addProperty("url", url);
            return json;
        }

        private record Field(String name, String value, boolean inline) {
        }
    }
}
