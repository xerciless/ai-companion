package com.aicompanion.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Tiny client for the Claude Messages API, used only when you put an API key in
 * config/aicompanion.json. Runs off the game thread so the game never freezes.
 */
public final class ClaudeClient {
	private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private ClaudeClient() {
	}

	/** messages: list of {"role": "user"|"assistant", "content": "..."} (must start with a user message). */
	public static CompletableFuture<String> ask(String apiKey, String model, String system, List<JsonObject> messages) {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("max_tokens", 200);
		body.addProperty("system", system);
		JsonArray arr = new JsonArray();
		for (JsonObject m : messages) {
			arr.add(m);
		}
		body.add("messages", arr);

		HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
				.timeout(Duration.ofSeconds(30))
				.header("content-type", "application/json")
				.header("x-api-key", apiKey)
				.header("anthropic-version", "2023-06-01")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			if (response.statusCode() != 200) {
				throw new IllegalStateException("Claude API error " + response.statusCode() + ": " + shorten(response.body()));
			}
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			StringBuilder text = new StringBuilder();
			for (JsonElement el : json.getAsJsonArray("content")) {
				JsonObject block = el.getAsJsonObject();
				if (block.has("type") && "text".equals(block.get("type").getAsString())) {
					text.append(block.get("text").getAsString());
				}
			}
			return text.toString().trim();
		});
	}

	public static JsonObject message(String role, String content) {
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.addProperty("content", content);
		return m;
	}

	private static String shorten(String s) {
		return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) + "..." : s);
	}
}
