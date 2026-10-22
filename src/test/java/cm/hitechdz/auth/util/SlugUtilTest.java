package cm.hitechdz.auth.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlugUtilTest {

    @Test
    void removes_accents_symbols_and_merges_dashes() {
        assertThat(SlugUtil.slugify("Écouteurs Sans-Fil & Audio")).isEqualTo("ecouteurs-sans-fil-audio");
        assertThat(SlugUtil.slugify("  --Réseau  Wi-Fi 6 !!  ")).isEqualTo("reseau-wi-fi-6");
        assertThat(SlugUtil.slugify("Samsung Smart TV 55\" (2024)")).isEqualTo("samsung-smart-tv-55-2024");
        assertThat(SlugUtil.slugify("ÇA COÛTE 100 000 FCFA")).isEqualTo("ca-coute-100-000-fcfa");
    }

    @Test
    void empty_when_no_letter_or_digit() {
        assertThat(SlugUtil.slugify("&&& ---")).isEmpty();
        assertThat(SlugUtil.slugify(null)).isEmpty();
    }

    @Test
    void normalize_is_slug_with_spaces() {
        assertThat(SlugUtil.normalize("Écouteurs JBL")).isEqualTo("ecouteurs jbl");
    }
}
