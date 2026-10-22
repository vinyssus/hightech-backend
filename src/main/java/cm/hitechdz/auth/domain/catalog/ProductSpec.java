package cm.hitechdz.auth.domain.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Caractéristique technique clé/valeur (ex. « RAM » → « 16 Go »). */
@Embeddable
public class ProductSpec {

    @Column(name = "spec_key", nullable = false, length = 100)
    private String key;

    @Column(name = "spec_value", nullable = false, length = 500)
    private String value;

    protected ProductSpec() {}

    public ProductSpec(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
}
