package cm.hitechdz.auth.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlainTextTest {

    @Test
    void strips_html_and_scripts_but_keeps_text() {
        assertThat(PlainText.clean("Super <b>téléphone</b> !")).isEqualTo("Super téléphone !");
        assertThat(PlainText.clean("Avant<script>alert(1)</script> après")).isEqualTo("Avant après");
        assertThat(PlainText.clean("<img src=x onerror=alert(1)>Bien")).isEqualTo("Bien");
        assertThat(PlainText.clean("Ligne 1\r\n\r\n\r\n\r\nLigne 2")).isEqualTo("Ligne 1\n\nLigne 2");
        assertThat(PlainText.clean("3 < 5 et 6 > 4")).isEqualTo("3 5 et 6 4");
        assertThat(PlainText.clean("Prix : 450 000 FCFA, livré en 24 h.")).isEqualTo("Prix : 450 000 FCFA, livré en 24 h.");
        assertThat(PlainText.cleanLine("  Douala\nAkwa  ")).isEqualTo("Douala Akwa");
        assertThat(PlainText.clean(null)).isNull();
    }
}
