package com.intelligentresume.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BailianAiProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retriesTheNextConfiguredModelAfterQuotaExhaustion() throws Exception {
        List<String> requestedModels = new CopyOnWriteArrayList<>();
        startServer(exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.contains("\"model\":\"first\"")) {
                requestedModels.add("first");
                respond(exchange, 429, "{\"error\":{\"message\":\"quota exhausted\"}}");
            } else {
                requestedModels.add("second");
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"questions\\\":[\\\"What changed?\\\"]}\"}}]}");
            }
        });

        Map<String, Object> result = provider("first,second").invoke("ACHIEVEMENT_GUIDANCE", Map.of("content", "test"));

        assertThat(requestedModels).containsExactly("first", "second");
        assertThat(result).containsEntry("questions", List.of("What changed?"));
    }

    @Test
    void doesNotMaskAuthenticationFailuresByTryingAnotherModel() throws Exception {
        List<String> requestedModels = new CopyOnWriteArrayList<>();
        startServer(exchange -> {
            requestedModels.add("first");
            respond(exchange, 401, "{\"error\":{\"message\":\"invalid api key\"}}");
        });

        assertThatThrownBy(() -> provider("first,second").invoke("ACHIEVEMENT_GUIDANCE", Map.of()))
                .hasMessageContaining("HTTP 401");
        assertThat(requestedModels).containsExactly("first");
    }

    @Test
    void instructsMaterialGenerationToReturnACompleteDraftInTheInputLanguage() throws Exception {
        List<String> prompts = new CopyOnWriteArrayList<>();
        startServer(exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            prompts.add(request);
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"generatedResumeJson\\\":{\\\"basics\\\":{},\\\"work\\\":[],\\\"education\\\":[],\\\"skills\\\":[],\\\"projects\\\":[]},\\\"suggestions\\\":[]}\"}}]}");
        });

        provider("first").invoke("MATERIAL_RESUME_GENERATION", Map.of("rawMaterialText", "测试候选人"));

        assertThat(prompts).singleElement().satisfies(prompt ->
                assertThat(prompt).contains("Match the primary language of the input")
                        .contains("Return a best-effort populated generatedResumeJson")
                        .contains("never replace the draft with an explanation"));
    }

    private BailianAiProvider provider(String models) {
        return new BailianAiProvider(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(),
                new ObjectMapper(), "test-key", models);
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> handler.handle(exchange));
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
