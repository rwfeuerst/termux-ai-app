package com.termux.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI Client for Claude (Anthropic Messages API) and Gemini integration in Termux+
 *
 * Claude integration uses the official Anthropic Messages API:
 *   https://api.anthropic.com/v1/messages
 *   Auth: x-api-key header with API key from console.anthropic.com
 *
 * Gemini integration uses the Google Generative Language API:
 *   https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent
 *   Auth: x-goog-api-key header
 */
public class AIClient {
    private static final String TAG = "TermuxAI";
    private static final String PREFS_NAME = "termux_ai_credentials";
    private static final String PREF_CLAUDE_API_KEY = "claude_api_key";
    private static final String PREF_AI_PROVIDER = "ai_provider";
    private static final String PREF_GEMINI_API_KEY = "gemini_api_key";
    private static final String PREF_CLAUDE_MODEL = "claude_model";

    // Correct Anthropic API endpoint
    public static final String ANTHROPIC_API_BASE_URL = "https://api.anthropic.com/v1";
    public static final String ANTHROPIC_API_VERSION = "2023-06-01";
    public static final String DEFAULT_CLAUDE_MODEL = "claude-sonnet-4-20250514";

    // Gemini endpoint (this was already correct)
    public static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Context context;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final SharedPreferences prefs;
    private final Handler mainHandler;

    private String claudeApiKey;
    private String geminiApiKey;
    private String claudeModel;
    private String currentProvider; // "claude" or "gemini"

    private AIClientListener listener;

    public interface AIClientListener {
        void onSuggestionReceived(String suggestion, float confidence);
        void onErrorAnalysis(String error, String analysis, String[] solutions);
        void onCodeGenerated(String code, String language);
        void onConnectionStatusChanged(boolean connected);
        void onAuthenticationRequired();
    }

    public AIClient(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.gson = new Gson();
        this.mainHandler = new Handler(Looper.getMainLooper());

        // Use encrypted SharedPreferences for credential storage
        this.prefs = EncryptedPreferencesManager.getEncryptedPrefs(context, PREFS_NAME);

        // Migrate any old plaintext prefs from the original broken config
        EncryptedPreferencesManager.migratePlaintextToEncrypted(
            context,
            "termux_ai_prefs",   // old plaintext prefs name
            PREFS_NAME           // new encrypted prefs name (different!)
        );

        // Build OkHttpClient
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)  // Claude responses can take a moment
            .build();

        loadCredentials();
    }

    public void setListener(AIClientListener listener) {
        this.listener = listener;
    }

    // =========================================================================
    // Credential Management
    // =========================================================================

    private void loadCredentials() {
        claudeApiKey = prefs.getString(PREF_CLAUDE_API_KEY, null);
        geminiApiKey = prefs.getString(PREF_GEMINI_API_KEY, null);
        currentProvider = prefs.getString(PREF_AI_PROVIDER, "claude");
        claudeModel = prefs.getString(PREF_CLAUDE_MODEL, DEFAULT_CLAUDE_MODEL);
    }

    private void saveCredentials() {
        prefs.edit()
            .putString(PREF_CLAUDE_API_KEY, claudeApiKey)
            .putString(PREF_GEMINI_API_KEY, geminiApiKey)
            .putString(PREF_AI_PROVIDER, currentProvider)
            .putString(PREF_CLAUDE_MODEL, claudeModel)
            .apply();
    }

    /**
     * Set the Claude API key. Get yours from:
     * https://console.anthropic.com/settings/keys
     */
    public void setClaudeApiKey(String apiKey) {
        this.claudeApiKey = apiKey;
        saveCredentials();
    }

    /**
     * Set the Gemini API key.
     */
    public void setGeminiApiKey(String apiKey) {
        this.geminiApiKey = apiKey;
        saveCredentials();
    }

    /**
     * Set the Claude model to use.
     * Options: claude-sonnet-4-20250514, claude-haiku-4-5-20251001, etc.
     */
    public void setClaudeModel(String model) {
        this.claudeModel = model;
        saveCredentials();
    }

    /**
     * Switch between "claude" and "gemini" providers.
     */
    public void setProvider(String provider) {
        if ("claude".equals(provider) || "gemini".equals(provider)) {
            this.currentProvider = provider;
            saveCredentials();
        }
    }

    public String getCurrentProvider() {
        return currentProvider;
    }

    public String getClaudeModel() {
        return claudeModel != null ? claudeModel : DEFAULT_CLAUDE_MODEL;
    }

    public boolean isAuthenticated() {
        if ("gemini".equals(currentProvider)) {
            return geminiApiKey != null && !geminiApiKey.isEmpty();
        }
        return claudeApiKey != null && !claudeApiKey.isEmpty();
    }

    /**
     * Validate the Claude API key by making a lightweight request.
     */
    public void validateClaudeApiKey(AuthCallback callback) {
        if (claudeApiKey == null || claudeApiKey.isEmpty()) {
            callback.onError("No API key set");
            return;
        }

        // Send a minimal request to validate the key
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", getClaudeModel());
        requestBody.addProperty("max_tokens", 10);

        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", "hi");
        messages.add(msg);
        requestBody.add("messages", messages);

        RequestBody body = RequestBody.create(gson.toJson(requestBody), JSON);
        Request request = new Request.Builder()
            .url(ANTHROPIC_API_BASE_URL + "/messages")
            .addHeader("x-api-key", claudeApiKey)
            .addHeader("anthropic-version", ANTHROPIC_API_VERSION)
            .addHeader("content-type", "application/json")
            .post(body)
            .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mainHandler.post(() -> callback.onError("Network error: " + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    mainHandler.post(callback::onSuccess);
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    String errorMsg;
                    switch (response.code()) {
                        case 401:
                            errorMsg = "Invalid API key. Check your key at console.anthropic.com";
                            break;
                        case 403:
                            errorMsg = "API key lacks permission. Check your Anthropic account.";
                            break;
                        case 429:
                            errorMsg = "Rate limited. Try again in a moment.";
                            break;
                        default:
                            errorMsg = "API error " + response.code() + ": " + errorBody;
                    }
                    mainHandler.post(() -> callback.onError(errorMsg));
                }
                if (response.body() != null) response.body().close();
            }
        });
    }

    // =========================================================================
    // Claude Messages API - Core Request Method
    // =========================================================================

    /**
     * Send a request to the Anthropic Messages API.
     *
     * API docs: https://docs.anthropic.com/en/api/messages
     */
    private void sendClaudeRequest(String systemPrompt, String userMessage,
                                    int maxTokens, RequestCallback callback) {
        if (claudeApiKey == null || claudeApiKey.isEmpty()) {
            callback.onError("Claude API key not configured");
            return;
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", getClaudeModel());
        requestBody.addProperty("max_tokens", maxTokens);

        // System prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            requestBody.addProperty("system", systemPrompt);
        }

        // Messages array
        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", userMessage);
        messages.add(msg);
        requestBody.add("messages", messages);

        RequestBody body = RequestBody.create(gson.toJson(requestBody), JSON);
        Request request = new Request.Builder()
            .url(ANTHROPIC_API_BASE_URL + "/messages")
            .addHeader("x-api-key", claudeApiKey)
            .addHeader("anthropic-version", ANTHROPIC_API_VERSION)
            .addHeader("content-type", "application/json")
            .post(body)
            .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("Request failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                        callback.onSuccess(jsonResponse);
                    } else if (response.code() == 401) {
                        claudeApiKey = null;
                        saveCredentials();
                        mainHandler.post(() -> {
                            if (listener != null) {
                                listener.onAuthenticationRequired();
                            }
                        });
                        callback.onError("API key invalid or expired");
                    } else if (response.code() == 429) {
                        callback.onError("Rate limited. Please wait a moment.");
                    } else if (response.code() == 529) {
                        callback.onError("Anthropic API is temporarily overloaded. Try again.");
                    } else {
                        String errorBody = response.body() != null ? response.body().string() : "unknown";
                        callback.onError("API error " + response.code() + ": " + errorBody);
                    }
                } finally {
                    if (response.body() != null) response.body().close();
                }
            }
        });
    }

    /**
     * Extract text content from a Claude Messages API response.
     *
     * Response format:
     * {
     *   "content": [
     *     { "type": "text", "text": "..." }
     *   ],
     *   "model": "...",
     *   "stop_reason": "end_turn",
     *   "usage": { "input_tokens": N, "output_tokens": N }
     * }
     */
    private String extractClaudeText(JsonObject response) {
        JsonArray content = response.getAsJsonArray("content");
        if (content != null && content.size() > 0) {
            JsonObject firstBlock = content.get(0).getAsJsonObject();
            if ("text".equals(firstBlock.get("type").getAsString())) {
                return firstBlock.get("text").getAsString();
            }
        }
        return "";
    }

    /**
     * Extract JSON from Claude's text response.
     * Claude may wrap JSON in markdown code fences, so we strip those.
     */
    private JsonObject extractClaudeJson(JsonObject response) {
        String text = extractClaudeText(response);
        text = text.trim();

        // Strip markdown code fences
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        text = text.trim();

        return gson.fromJson(text, JsonObject.class);
    }

    // =========================================================================
    // Analyze Command
    // =========================================================================

    public void analyzeCommand(String command, String context, AnalysisCallback callback) {
        loadCredentials();

        boolean shouldFilter = prefs.getBoolean("command_filtering_enabled", true);
        String filteredCommand = shouldFilter ? PrivacyGuard.filterCommand(command) : command;
        String filteredContext = shouldFilter ? PrivacyGuard.filter(context) : context;

        if (!isAuthenticated()) {
            mainHandler.post(() -> {
                if (listener != null) listener.onAuthenticationRequired();
            });
            return;
        }

        if ("gemini".equals(currentProvider)) {
            analyzeCommandGemini(filteredCommand, filteredContext, callback);
        } else {
            analyzeCommandClaude(filteredCommand, filteredContext, callback);
        }
    }

    private void analyzeCommandClaude(String command, String context, AnalysisCallback callback) {
        String systemPrompt = "You are a terminal command assistant integrated into a mobile terminal app. " +
            "Analyze commands and provide helpful suggestions. " +
            "Respond ONLY with a JSON object containing 'suggestion' (string) and 'confidence' (float 0.0-1.0).";

        String userMessage = "Analyze this command: " + command + "\nContext: " + context;

        sendClaudeRequest(systemPrompt, userMessage, 512, new RequestCallback() {
            @Override
            public void onSuccess(JsonObject response) {
                try {
                    JsonObject json = extractClaudeJson(response);
                    String suggestion = json.get("suggestion").getAsString();
                    float confidence = json.has("confidence") ?
                        json.get("confidence").getAsFloat() : 0.8f;

                    mainHandler.post(() -> {
                        callback.onSuggestion(suggestion, confidence);
                        if (listener != null) listener.onSuggestionReceived(suggestion, confidence);
                    });
                } catch (Exception e) {
                    // If JSON parsing fails, use the raw text as the suggestion
                    String rawText = extractClaudeText(response);
                    mainHandler.post(() -> callback.onSuggestion(rawText, 0.5f));
                }
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> callback.onError(error));
            }
        });
    }

    private void analyzeCommandGemini(String command, String context, AnalysisCallback callback) {
        String prompt = "Analyze this command: " + command + "\nContext: " + context +
            "\nProvide a suggestion for improvement or explanation. " +
            "Return ONLY JSON with 'suggestion' (string) and 'confidence' (float 0.0-1.0) fields. No markdown.";

        sendGeminiRequest(prompt, new RequestCallback() {
            @Override
            public void onSuccess(JsonObject response) {
                try {
                    JsonObject json = parseGeminiResponse(response);
                    String suggestion = json.get("suggestion").getAsString();
                    float confidence = json.has("confidence") ? json.get("confidence").getAsFloat() : 0.8f;

                    mainHandler.post(() -> {
                        callback.onSuggestion(suggestion, confidence);
                        if (listener != null) listener.onSuggestionReceived(suggestion, confidence);
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onError("Failed to parse Gemini response: " + e.getMessage()));
                }
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> callback.onError(error));
            }
        }, error -> mainHandler.post(() -> callback.onError(error)));
    }

    // =========================================================================
    // Analyze Error
    // =========================================================================

    public void analyzeError(String command, String errorOutput, String context, ErrorCallback callback) {
        loadCredentials();

        boolean shouldFilter = prefs.getBoolean("command_filtering_enabled", true);
        String filteredCommand = shouldFilter ? PrivacyGuard.filterCommand(command) : command;
        String filteredError = shouldFilter ? PrivacyGuard.filter(errorOutput) : errorOutput;
        String filteredContext = shouldFilter ? PrivacyGuard.filter(context) : context;

        if (!isAuthenticated()) {
            mainHandler.post(() -> {
                if (listener != null) listener.onAuthenticationRequired();
            });
            return;
        }

        if ("gemini".equals(currentProvider)) {
            analyzeErrorGemini(filteredCommand, filteredError, filteredContext, callback);
        } else {
            analyzeErrorClaude(filteredCommand, filteredError, filteredContext, callback);
        }
    }

    private void analyzeErrorClaude(String command, String errorOutput, String context, ErrorCallback callback) {
        String systemPrompt = "You are a terminal error diagnostics assistant. " +
            "Analyze command errors and provide actionable solutions. " +
            "Respond ONLY with a JSON object containing 'analysis' (string) and 'solutions' (array of strings).";

        String userMessage = "Command: " + command +
            "\nError output: " + errorOutput +
            "\nContext: " + context;

        sendClaudeRequest(systemPrompt, userMessage, 1024, new RequestCallback() {
            @Override
            public void onSuccess(JsonObject response) {
                try {
                    JsonObject json = extractClaudeJson(response);
                    String analysis = json.get("analysis").getAsString();
                    String[] solutions = gson.fromJson(json.get("solutions"), String[].class);

                    mainHandler.post(() -> {
                        callback.onAnalysis(analysis, solutions);
                        if (listener != null) listener.onErrorAnalysis(errorOutput, analysis, solutions);
                    });
                } catch (Exception e) {
                    String rawText = extractClaudeText(response);
                    mainHandler.post(() -> callback.onAnalysis(rawText, new String[]{}));
                }
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> callback.onError(error));
            }
        });
    }

    private void analyzeErrorGemini(String command, String errorOutput, String context, ErrorCallback callback) {
        String prompt = "Command: " + command + "\nError: " + errorOutput + "\nContext: " + context +
            "\nAnalyze and provide solutions. Return ONLY JSON with 'analysis' (string) and 'solutions' (string array). No markdown.";

        sendGeminiRequest(prompt, new RequestCallback() {
            @Override
            public void onSuccess(JsonObject response) {
                try {
                    JsonObject json = parseGeminiResponse(response);
                    String analysis = json.get("analysis").getAsString();
                    String[] solutions = gson.fromJson(json.get("solutions"), String[].class);

                    mainHandler.post(() -> {
                        callback.onAnalysis(analysis, solutions);
                        if (listener != null) listener.onErrorAnalysis(errorOutput, analysis, solutions);
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onError("Failed to parse Gemini response: " + e.getMessage()));
                }
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> callback.onError(error));
            }
        }, error -> mainHandler.post(() -> callback.onError(error)));
    }

    // =========================================================================
    // Generate Code
    // =========================================================================

    public void generateCode(String description, String language, String context, CodeCallback callback) {
        loadCredentials();

        boolean shouldFilter = prefs.getBoolean("command_filtering_enabled", true);
        String filteredDescription = shouldFilter ? PrivacyGuard.filter(description) : description;
        String filteredContext = shouldFilter ? PrivacyGuard.filter(context) : context;

        if (!isAuthenticated()) {
            mainHandler.post(() -> {
                if (listener != null) listener.onAuthenticationRequired();
            });
            return;
        }

        if ("gemini".equals(currentProvider)) {
            generateCodeGemini(filteredDescription, language, filteredContext, callback);
        } else {
            generateCodeClaude(filteredDescription, language, filteredContext, callback);
        }
    }

    private void generateCodeClaude(String description, String language, String context, CodeCallback callback) {
        String systemPrompt = "You are a code generation assistant for a mobile terminal environment. " +
            "Generate clean, well-commented code. " +
            "Respond ONLY with a JSON object containing 'code' (string) and 'language' (string).";

        String userMessage = "Generate " + language + " code for: " + description +
            "\nContext: " + context;

        sendClaudeRequest(systemPrompt, userMessage, 4096, new RequestCallback() {
            @Override
            public void onSuccess(JsonObject response) {
                try {
                    JsonObject json = extractClaudeJson(response);
                    String code = json.get("code").getAsString();
                    String detectedLanguage = json.has("language") ?
                        json.get("language").getAsString() : language;

                    mainHandler.post(() -> {
                        callback.onCodeGenerated(code, detectedLanguage);
                        if (listener != null) listener.onCodeGenerated(code, detectedLanguage);
                    });
                } catch (Exception e) {
                    // If JSON fails, return the raw text as code
                    String rawText = extractClaudeText(response);
                    mainHandler.post(() -> callback.onCodeGenerated(rawText, language));
                }
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> callback.onError(error));
            }
        });
    }

    private void generateCodeGemini(String description, String language, String context, CodeCallback callback) {
        String prompt = "Write " + language + " code for: " + description + "\nContext: " + context +
            "\nReturn ONLY JSON with 'code' (string) and 'language' (string). No markdown.";

        sendGeminiRequest(prompt, new RequestCallback() {
            @Override
            public void onSuccess(JsonObject response) {
                try {
                    JsonObject json = parseGeminiResponse(response);
                    String code = json.get("code").getAsString();
                    String detectedLanguage = json.has("language") ? json.get("language").getAsString() : language;

                    mainHandler.post(() -> {
                        callback.onCodeGenerated(code, detectedLanguage);
                        if (listener != null) listener.onCodeGenerated(code, detectedLanguage);
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onError("Failed to parse Gemini response: " + e.getMessage()));
                }
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> callback.onError(error));
            }
        }, error -> mainHandler.post(() -> callback.onError(error)));
    }

    // =========================================================================
    // Context Update (sends terminal context for AI awareness)
    // =========================================================================

    /**
     * Send context update to Claude for ongoing awareness.
     * Uses a lightweight request to keep the AI informed of terminal state.
     */
    public void sendContextUpdate(String workingDirectory, String currentCommand, String[] recentCommands) {
        if (!"claude".equals(currentProvider) || !isAuthenticated()) return;

        // Context updates are fire-and-forget informational requests
        // They help the next analyzeCommand/analyzeError call have better context
        // We store this locally rather than making an API call for each keystroke
        prefs.edit()
            .putString("last_working_dir", workingDirectory)
            .putString("last_command", currentCommand)
            .apply();
    }

    // =========================================================================
    // Gemini Request Method (unchanged - was already correct)
    // =========================================================================

    private void sendGeminiRequest(String prompt, RequestCallback callback,
                                    AnalysisCallback.OnError errorCallback) {
        JsonObject content = new JsonObject();
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);
        content.add("parts", parts);

        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject requestBody = new JsonObject();
        requestBody.add("contents", contents);

        RequestBody body = RequestBody.create(gson.toJson(requestBody), JSON);
        Request request = new Request.Builder()
            .url(GEMINI_API_URL)
            .addHeader("x-goog-api-key", geminiApiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                errorCallback.onError("Gemini request failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                        callback.onSuccess(jsonResponse);
                    } else {
                        String errorBody = response.body() != null ? response.body().string() : "";
                        errorCallback.onError("Gemini error " + response.code() + ": " + errorBody);
                    }
                } finally {
                    if (response.body() != null) response.body().close();
                }
            }
        });
    }

    private JsonObject parseGeminiResponse(JsonObject response) {
        JsonArray candidates = response.getAsJsonArray("candidates");
        if (candidates != null && candidates.size() > 0) {
            JsonObject candidate = candidates.get(0).getAsJsonObject();
            JsonObject content = candidate.getAsJsonObject("content");
            JsonArray parts = content.getAsJsonArray("parts");
            String text = parts.get(0).getAsJsonObject().get("text").getAsString();

            // Strip markdown code fences
            text = text.trim();
            if (text.startsWith("```json")) {
                text = text.substring(7);
            } else if (text.startsWith("```")) {
                text = text.substring(3);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }

            return gson.fromJson(text.trim(), JsonObject.class);
        }
        throw new RuntimeException("No candidates in Gemini response");
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public void shutdown() {
        // No WebSocket to close anymore - all requests are stateless HTTP
        Log.d(TAG, "AIClient shutdown");
    }

    // =========================================================================
    // Callback Interfaces
    // =========================================================================

    public interface AuthCallback {
        void onSuccess();
        void onError(String error);
    }

    public interface AnalysisCallback {
        void onSuggestion(String suggestion, float confidence);
        void onError(String error);

        interface OnError {
            void onError(String error);
        }
    }

    public interface ErrorCallback {
        void onAnalysis(String analysis, String[] solutions);
        void onError(String error);
    }

    public interface CodeCallback {
        void onCodeGenerated(String code, String language);
        void onError(String error);
    }

    private interface RequestCallback {
        void onSuccess(JsonObject response);
        void onError(String error);
    }
}
