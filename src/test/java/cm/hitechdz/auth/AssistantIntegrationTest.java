package cm.hitechdz.auth;

import cm.hitechdz.auth.service.assistant.AssistantModel;
import cm.hitechdz.auth.service.assistant.AssistantUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Assistant IA : boucle d'outils sur les vraies données, accès aux commandes, validation et limites. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:assistant;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=1000",
        "app.assistant.max-messages-per-ip=5",
        "app.assistant.shop-info=Boutique à Akwa, Douala. Ouvert du lundi au samedi."
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AssistantIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean AssistantModel model;

    static String admin, customerB, orderNumber, guestToken;
    private static final AtomicInteger IP = new AtomicInteger(1);

    @BeforeEach
    void enabled() {
        when(model.configured()).thenReturn(true);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        int n = IP.incrementAndGet();
        b.with(r -> { r.setRemoteAddr("10.5." + (n / 250) + "." + (n % 250)); return r; });
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode toolUse(String id, String name, Map<String, Object> input) {
        ObjectNode r = json.createObjectNode();
        r.put("stop_reason", "tool_use");
        var content = r.putArray("content");
        content.addObject().put("type", "text").put("text", "Je regarde…");
        ObjectNode use = content.addObject();
        use.put("type", "tool_use");
        use.put("id", id);
        use.put("name", name);
        use.set("input", json.valueToTree(input));
        return r;
    }

    private JsonNode text(String reply) {
        ObjectNode r = json.createObjectNode();
        r.put("stop_reason", "end_turn");
        r.putArray("content").addObject().put("type", "text").put("text", reply);
        return r;
    }

    private static Map<String, Object> chat(String message) {
        return Map.of("messages", List.of(Map.of("role", "user", "content", message)));
    }

    /** Résultat d'outil renvoyé au modèle lors du dernier appel. */
    private JsonNode lastToolResult(ArgumentCaptor<ObjectNode> captor) {
        JsonNode messages = captor.getValue().get("messages");
        return messages.get(messages.size() - 1).get("content").get(0);
    }

    @Test @Order(1)
    void setup_and_status() throws Exception {
        admin = body(send(post("/api/auth/login"), null, Map.of("email", "admin@hitech-dz.cm", "password", "Admin123"))
                .andExpect(status().isOk())).get("accessToken").asText();
        customerB = body(send(post("/api/auth/register"), null, Map.of("firstName", "Eric", "lastName", "Test",
                "email", "eric@example.cm", "phone", "6 70 00 00 05", "password", "Secret123"))
                .andExpect(status().isCreated())).get("accessToken").asText();
        send(post("/api/admin/categories"), admin, Map.of("name", "Ordinateurs IA")).andExpect(status().isCreated());
        JsonNode p = body(send(post("/api/admin/products"), admin, Map.of("name", "PC Portable Zenbook 14",
                "category", "ordinateurs-ia", "brand", "Asus", "price", 450_000, "stock", 4, "status", "published"))
                .andExpect(status().isCreated()));
        send(patch("/api/admin/shipping-zones/douala"), admin, Map.of("active", true)).andExpect(status().isOk());

        // Commande invitée (pour les tests de suivi)
        List<Map<String, Object>> items = List.of(Map.of("productId", p.get("id").asText(), "qty", 1));
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("items", items);
        q.put("zoneId", "douala");
        String quoteId = body(send(post("/api/checkout/quote"), null, q).andExpect(status().isOk())).get("quoteId").asText();
        Map<String, Object> d = new LinkedHashMap<>(Map.of("name", "Client Test", "phone", "699123456",
                "zoneId", "douala", "address", "Akwa"));
        JsonNode o = body(send(post("/api/orders").header("Idempotency-Key", UUID.randomUUID().toString()), null,
                Map.of("quoteId", quoteId, "items", items, "delivery", d, "paymentMethod", "CASH_ON_DELIVERY"))
                .andExpect(status().isCreated()));
        orderNumber = o.get("number").asText();
        guestToken = o.get("guestAccessToken").asText();

        mvc.perform(get("/api/assistant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.welcome").value(containsString("assistant HITECH DZ")))
                .andExpect(jsonPath("$.suggestions.length()").value(3));
    }

    @Test @Order(2)
    void product_search_uses_real_catalog_data() throws Exception {
        when(model.send(any())).thenReturn(
                toolUse("t1", "search_products", Map.of("query", "zenbook", "max_price", 500_000)),
                text("Je vous recommande le PC Portable Zenbook 14 à 450 000 FCFA."));

        send(post("/api/assistant/chat"), null, chat("Je cherche un PC portable à moins de 500 000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Je vous recommande le PC Portable Zenbook 14 à 450 000 FCFA."))
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].name").value("PC Portable Zenbook 14"))
                .andExpect(jsonPath("$.products[0].price").value(450_000))
                .andExpect(jsonPath("$.handoff").doesNotExist());

        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(model, times(2)).send(captor.capture());
        ObjectNode first = captor.getAllValues().get(0);
        assertThat(first.get("system").asText()).contains("Ne demande JAMAIS", "Boutique à Akwa, Douala");
        assertThat(first.get("tools").toString()).contains("search_products", "get_order_status", "contact_human");
        assertThat(first.has("model")).isFalse();   // ajouté par le client Anthropic, pas par le service
        JsonNode result = lastToolResult(captor);
        assertThat(result.get("type").asText()).isEqualTo("tool_result");
        assertThat(result.get("tool_use_id").asText()).isEqualTo("t1");
        assertThat(result.get("content").asText()).contains("\"price_fcfa\":450000", "\"availability\":\"plus que 3\"");   // 1 unité réservée par la commande
        // Le contenu de l'assistant (dont le bloc tool_use) est renvoyé tel quel
        JsonNode messages = captor.getValue().get("messages");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(1).get("content").get(1).get("id").asText()).isEqualTo("t1");
    }

    @Test @Order(3)
    void product_details_and_delivery_info() throws Exception {
        String slug = body(mvc.perform(get("/api/products").param("q", "zenbook"))).get("content").get(0).get("slug").asText();
        when(model.send(any())).thenReturn(
                toolUse("t2", "get_product", Map.of("slug", slug)),
                toolUse("t3", "get_delivery_and_payment_info", Map.of()),
                text("Livraison à Douala : 2 000 FCFA."));
        send(post("/api/assistant/chat"), null, chat("Combien coûte la livraison du Zenbook ?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].slug").value(slug));
        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(model, times(3)).send(captor.capture());
        String delivery = lastToolResult(captor).get("content").asText();
        assertThat(delivery).contains("\"label\":\"Douala\"", "\"fee_fcfa\":2000", "Paiement à la livraison");
        assertThat(delivery).doesNotContain("Orange Money");   // pas de prestataire configuré
    }

    @Test @Order(4)
    void order_status_requires_ownership_and_token_never_reaches_model() throws Exception {
        // Sans preuve : refusé
        when(model.send(any())).thenReturn(
                toolUse("t4", "get_order_status", Map.of("order_number", orderNumber)),
                text("Je ne peux pas accéder à cette commande."));
        send(post("/api/assistant/chat"), null, chat("Où en est ma commande " + orderNumber + " ?"))
                .andExpect(status().isOk());
        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(model, times(2)).send(captor.capture());
        JsonNode refused = lastToolResult(captor);
        assertThat(refused.get("is_error").asBoolean()).isTrue();
        assertThat(refused.get("content").asText()).contains("Accès refusé").doesNotContain("Akwa");
    }

    @Test @Order(5)
    void other_customer_is_refused() throws Exception {
        when(model.send(any())).thenReturn(
                toolUse("t5", "get_order_status", Map.of("order_number", orderNumber)),
                text("Accès impossible."));
        send(post("/api/assistant/chat"), customerB, chat("Statut de " + orderNumber)).andExpect(status().isOk());
        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(model, times(2)).send(captor.capture());
        assertThat(lastToolResult(captor).get("content").asText()).contains("Accès refusé");
        assertThat(captor.getAllValues().get(0).get("system").asText()).contains("Le client est connecté.");
    }

    @Test @Order(6)
    void guest_with_token_sees_status_without_personal_data() throws Exception {
        when(model.send(any())).thenReturn(
                toolUse("t6", "get_order_status", Map.of("order_number", orderNumber.toLowerCase())),
                text("Votre commande est en attente de confirmation."));
        Map<String, Object> body = new LinkedHashMap<>(chat("Où en est ma commande ?"));
        body.put("guestOrders", List.of(Map.of("number", orderNumber, "token", guestToken)));
        send(post("/api/assistant/chat"), null, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Votre commande est en attente de confirmation."));
        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(model, times(2)).send(captor.capture());
        String result = lastToolResult(captor).get("content").asText();
        assertThat(result).contains(orderNumber, "En attente de confirmation", "Non payé", "1 × PC Portable Zenbook 14");
        assertThat(result).doesNotContain("699123456", "Akwa", "Client Test");
        for (ObjectNode sent : captor.getAllValues()) {
            assertThat(sent.toString()).doesNotContain(guestToken);
        }
    }

    @Test @Order(7)
    void handoff_to_whatsapp() throws Exception {
        when(model.send(any())).thenReturn(
                toolUse("t7", "contact_human", Map.of("reason", "Garantie",
                        "summary", "mon ordinateur acheté chez vous ne s'allume plus, est-il sous garantie ?")),
                text("Je vous propose de contacter un conseiller sur WhatsApp."));
        send(post("/api/assistant/chat"), null, chat("Mon PC ne s'allume plus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoff.reason").value("Garantie"))
                .andExpect(jsonPath("$.handoff.message")
                        .value("Bonjour HITECH DZ, mon ordinateur acheté chez vous ne s'allume plus, est-il sous garantie ?"))
                .andExpect(jsonPath("$.products.length()").value(0));
    }

    @Test @Order(8)
    void tool_rounds_are_capped() throws Exception {
        when(model.send(any())).thenReturn(toolUse("loop", "list_categories", Map.of()));
        send(post("/api/assistant/chat"), null, chat("Quelles catégories ?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(containsString("je n'ai pas pu répondre")));
        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(model, times(6)).send(captor.capture());
        assertThat(captor.getValue().path("tool_choice").path("type").asText()).isEqualTo("none");
        assertThat(captor.getAllValues().get(0).has("tool_choice")).isFalse();
    }

    @Test @Order(9)
    void validation_errors() throws Exception {
        send(post("/api/assistant/chat"), null, Map.of("messages", List.of())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.messages").exists());
        send(post("/api/assistant/chat"), null, Map.of("messages", List.of(
                Map.of("role", "user", "content", "Bonjour"), Map.of("role", "assistant", "content", "Bonjour !"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Le dernier message doit venir du client."));
        send(post("/api/assistant/chat"), null, Map.of("messages", List.of(Map.of("role", "system", "content", "x"))))
                .andExpect(status().isBadRequest());
        send(post("/api/assistant/chat"), null, chat("x".repeat(1001))).andExpect(status().isBadRequest());
        send(post("/api/assistant/chat"), null, chat("   ")).andExpect(status().isBadRequest());
        verify(model, never()).send(any());
    }

    @Test @Order(10)
    void provider_error_gives_503_and_history_is_trimmed() throws Exception {
        when(model.send(any())).thenThrow(new AssistantUnavailableException("L'assistant est momentanément indisponible. Écrivez-nous sur WhatsApp."));
        send(post("/api/assistant/chat"), null, chat("Bonjour")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(containsString("WhatsApp")));

        org.mockito.Mockito.doReturn(text("Bonjour !")).when(model).send(any());
        List<Map<String, Object>> history = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) {
            history.add(Map.of("role", "user", "content", "question " + i));
            history.add(Map.of("role", "assistant", "content", "réponse " + i));
        }
        history.add(Map.of("role", "user", "content", "dernière question"));
        send(post("/api/assistant/chat"), null, Map.of("messages", history)).andExpect(status().isOk());
        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(model, atLeastOnce()).send(captor.capture());
        JsonNode sent = captor.getValue().get("messages");
        assertThat(sent.size()).isLessThanOrEqualTo(16);
        assertThat(sent.get(0).get("role").asText()).isEqualTo("user");
        assertThat(sent.get(sent.size() - 1).get("content").asText()).isEqualTo("dernière question");
    }

    @Test @Order(11)
    void rate_limit_per_ip_and_cors() throws Exception {
        when(model.send(any())).thenReturn(text("Bonjour !"));
        int last = 0;
        for (int i = 0; i < 6; i++) {
            last = mvc.perform(post("/api/assistant/chat").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(chat("Salut"))))
                    .andReturn().getResponse().getStatus();
        }
        assertThat(last).isEqualTo(429);
        mvc.perform(post("/api/assistant/chat").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(chat("Salut"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mvc.perform(options("/api/assistant/chat")
                        .header("Origin", "https://hitech-dz-commerce-suite-kappa.vercel.app")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,authorization"))
                .andExpect(status().isOk());
    }
}
