package cm.hitechdz.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * Le site appelle l'API via son relais (/backend) : sans ce filtre, tous les visiteurs auraient
 * l'adresse du relais et partageraient les mêmes limites anti-abus. Le relais envoie l'IP réelle
 * dans X-Client-IP avec X-Relay-Secret ; l'IP n'est retenue que si le secret correspond
 * (comparaison à temps constant) et si c'est une adresse IP littérale. Sinon l'en-tête est ignoré :
 * un client ne peut pas choisir son IP.
 */
public class RelayClientIpFilter extends OncePerRequestFilter {

    public static final String SECRET_HEADER = "X-Relay-Secret";
    public static final String CLIENT_IP_HEADER = "X-Client-IP";
    public static final int MIN_SECRET_LENGTH = 32;

    private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private final byte[] secret;

    public RelayClientIpFilter(String secret) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean enabled() {
        return secret.length >= MIN_SECRET_LENGTH;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = enabled() ? trustedClientIp(request) : null;
        chain.doFilter(ip == null ? request : new ClientIpRequest(request, ip), response);
    }

    private String trustedClientIp(HttpServletRequest request) {
        String given = request.getHeader(SECRET_HEADER);
        if (given == null || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return literalIp(request.getHeader(CLIENT_IP_HEADER));
    }

    /** Adresse IP littérale normalisée, sinon null (aucune résolution DNS). */
    static String literalIp(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (IPV4.matcher(v).matches()) return v;
        if (v.contains(":") && IPV6.matcher(v).matches()) {
            try {
                return InetAddress.getByName(v).getHostAddress();   // littéral IPv6 : pas de DNS
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static final class ClientIpRequest extends HttpServletRequestWrapper {
        private final String ip;

        ClientIpRequest(HttpServletRequest request, String ip) {
            super(request);
            this.ip = ip;
        }

        @Override public String getRemoteAddr() { return ip; }
        @Override public String getRemoteHost() { return ip; }
    }
}
