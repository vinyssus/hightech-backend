package cm.hitechdz.auth;

import cm.hitechdz.auth.security.RelayClientIpFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Les visiteurs passant par le relais gardent chacun leur limite anti-abus ; l'IP ne se falsifie pas sans secret. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:relayip;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.secret=dGVzdC1zZWNyZXQtcG91ci1oaXRlY2gtZHotYXV0aC0yNTYtYml0cy1taW5pbXVt",
        "app.rate-limit.login.max-requests=2",
        "app.relay.secret=" + RelayClientIpIntegrationTest.SECRET
})
@AutoConfigureMockMvc
class RelayClientIpIntegrationTest {

    static final String SECRET = "relais-secret-de-test-0123456789abcdef";
    private static final String BAD_LOGIN = "{\"email\":\"personne@example.cm\",\"password\":\"Mauvais123\"}";

    @Autowired MockMvc mvc;

    private MockHttpServletRequestBuilder login(String secret, String clientIp) {
        MockHttpServletRequestBuilder b = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(BAD_LOGIN)
                .with(r -> { r.setRemoteAddr("44.1.1.1"); return r; });   // adresse du relais
        if (secret != null) b.header(RelayClientIpFilter.SECRET_HEADER, secret);
        if (clientIp != null) b.header(RelayClientIpFilter.CLIENT_IP_HEADER, clientIp);
        return b;
    }

    @Test
    void each_relayed_visitor_has_its_own_limit_and_ip_cannot_be_forged() throws Exception {
        // Visiteur A via le relais : bloqué à la 3e tentative
        mvc.perform(login(SECRET, "102.244.10.1")).andExpect(status().isUnauthorized());
        mvc.perform(login(SECRET, "102.244.10.1")).andExpect(status().isUnauthorized());
        mvc.perform(login(SECRET, "102.244.10.1")).andExpect(status().isTooManyRequests());
        // Visiteur B via le même relais : non concerné par le blocage de A
        mvc.perform(login(SECRET, "2c0f:2a80::1")).andExpect(status().isUnauthorized());

        // Sans le bon secret, X-Client-IP est ignoré : on reste sur l'adresse du relais
        mvc.perform(login(null, "1.2.3.4")).andExpect(status().isUnauthorized());
        mvc.perform(login("mauvais-secret-mauvais-secret-mauvais", "5.6.7.8")).andExpect(status().isUnauthorized());
        mvc.perform(login(null, "9.9.9.9")).andExpect(status().isTooManyRequests());
    }

    @Test
    void only_literal_ips_are_accepted_and_short_secret_disables() throws Exception {
        RelayClientIpFilter filter = new RelayClientIpFilter(SECRET);
        assertThat(remoteAddrSeen(filter, SECRET, " 41.202.207.5 ")).isEqualTo("41.202.207.5");
        assertThat(remoteAddrSeen(filter, SECRET, "google.com")).isEqualTo("10.0.0.1");
        assertThat(remoteAddrSeen(filter, SECRET, "999.1.1.1")).isEqualTo("10.0.0.1");
        assertThat(remoteAddrSeen(filter, SECRET, "1.2.3.4, 5.6.7.8")).isEqualTo("10.0.0.1");
        assertThat(remoteAddrSeen(filter, SECRET.toUpperCase(), "41.202.207.5")).isEqualTo("10.0.0.1");

        RelayClientIpFilter weak = new RelayClientIpFilter("court");
        assertThat(weak.enabled()).isFalse();
        assertThat(remoteAddrSeen(weak, "court", "41.202.207.5")).isEqualTo("10.0.0.1");
    }

    private static String remoteAddrSeen(RelayClientIpFilter filter, String secret, String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/catalog");
        req.setRemoteAddr("10.0.0.1");
        req.addHeader(RelayClientIpFilter.SECRET_HEADER, secret);
        req.addHeader(RelayClientIpFilter.CLIENT_IP_HEADER, ip);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        return chain.getRequest().getRemoteAddr();
    }
}
