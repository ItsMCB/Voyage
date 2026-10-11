package org.libregalaxy.voyage.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.VoyageContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class WebhookService {

    private final VoyageContext context;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public WebhookService(@NotNull VoyageContext context) {
        this.context = context;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void notify(String event, CommandSender actor, String details) {
        String envUrl = System.getenv("VOYAGE_WEBHOOK_URL");
        String configUrl = context.getPlugin().getConfig().getString("webhook.url", "");
        String url = (envUrl != null && !envUrl.isBlank()) ? envUrl.trim() : configUrl.trim();

        List<String> events = context.getPlugin().getConfig().getStringList("webhook.events");

        if (url.isBlank() || !events.contains(event)) return;

        Bukkit.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
            try {
                String actorName = actor != null ? actor.getName() : "System";

                JsonObject embed = new JsonObject();
                embed.addProperty("title", event.replace("_", " "));
                embed.addProperty("description", "**Actor:** " + actorName + "\n**Details:** " + details);
                embed.addProperty("color", 15258703);

                JsonArray embeds = new JsonArray();
                embeds.add(embed);

                JsonObject payload = new JsonObject();
                payload.addProperty("username", "Voyage");
                payload.add("embeds", embeds);

                String json = gson.toJson(payload);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Voyage-Plugin/1.0 (Minecraft Server)")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 400) {
                    context.getPlugin().getLogger().warning("Webhook returned HTTP " + response.statusCode() + " for event: " + event);
                }
            } catch (Exception e) {
                context.getPlugin().getLogger().warning("Failed to send webhook: " + e.getMessage());
            }
        });
    }
}