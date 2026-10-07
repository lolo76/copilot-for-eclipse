// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.core.completion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Fill-in-the-middle completion client backed by a local Ollama server.
 *
 * <p>
 * Configuration (environment variables, with defaults):
 * <ul>
 * <li>{@code OLLAMA_HOST}: base URL, default {@code http://localhost:11434}</li>
 * <li>{@code OLLAMA_COMPLETION_MODEL}: default {@code qwen2.5-coder:1.5b}</li>
 * </ul>
 */
public class OllamaCompletionClient {

  private static final String DEFAULT_HOST = "http://localhost:11434";
  private static final String DEFAULT_MODEL = "qwen2.5-coder:1.5b";
  private static final int MAX_PREFIX_CHARS = 4000;
  private static final int MAX_SUFFIX_CHARS = 2000;
  private static final int MAX_NEW_TOKENS = 96;

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final String endpoint;
  private final String model;

  public OllamaCompletionClient() {
    this(getenvOr("OLLAMA_HOST", DEFAULT_HOST), getenvOr("OLLAMA_COMPLETION_MODEL", DEFAULT_MODEL));
  }

  public OllamaCompletionClient(String host, String model) {
    this.endpoint = stripTrailingSlash(host) + "/api/generate";
    this.model = model;
  }

  /**
   * Whether Ollama completion is selected. Enabled unless {@code COPILOT_COMPLETION_BACKEND=copilot}.
   */
  public static boolean isEnabled() {
    return !"copilot".equalsIgnoreCase(System.getenv("COPILOT_COMPLETION_BACKEND"));
  }

  /**
   * Returns the text to insert at the cursor, or an empty string when there is no useful suggestion.
   *
   * @param prefix text before the cursor
   * @param suffix text after the cursor
   * @return the suggestion, possibly empty
   * @throws IOException when the server cannot be reached or returns an error
   * @throws InterruptedException when the request is interrupted
   */
  public String complete(String prefix, String suffix) throws IOException, InterruptedException {
    String safePrefix = prefix.length() > MAX_PREFIX_CHARS ? prefix.substring(prefix.length() - MAX_PREFIX_CHARS)
        : prefix;
    String safeSuffix = suffix.length() > MAX_SUFFIX_CHARS ? suffix.substring(0, MAX_SUFFIX_CHARS) : suffix;

    JsonObject options = new JsonObject();
    options.addProperty("num_predict", MAX_NEW_TOKENS);
    options.addProperty("temperature", 0.2);

    JsonObject body = new JsonObject();
    body.addProperty("model", model);
    body.addProperty("prompt", safePrefix);
    body.addProperty("suffix", safeSuffix);
    body.addProperty("stream", false);
    body.addProperty("raw", true);
    body.add("options", options);

    HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build();

    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw new IOException("Ollama returned HTTP " + response.statusCode() + ": " + response.body());
    }
    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
    String text = json.has("response") ? json.get("response").getAsString() : "";
    return cleanSuggestion(text);
  }

  /**
   * Trims the model output: keeps at most a few lines and removes trailing whitespace-only lines.
   */
  static String cleanSuggestion(String raw) {
    String text = raw.replace("\r\n", "\n");
    // A completion is usually one statement or a few lines; cut at a blank line.
    int blank = text.indexOf("\n\n");
    if (blank >= 0) {
      text = text.substring(0, blank);
    }
    return text.stripTrailing();
  }

  private static String getenvOr(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
