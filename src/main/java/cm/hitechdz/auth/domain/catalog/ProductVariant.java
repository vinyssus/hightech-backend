package cm.hitechdz.auth.domain.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.ArrayList;
import java.util.List;

/** Combinaison vendable d'un produit (couleur × capacité × RAM), avec son propre stock. */
@Entity
@Table(name = "product_variants")
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, unique = true, length = 80)
    private String sku;

    @Column(length = 40)
    private String color;

    @Column(name = "color_hex", length = 9)
    private String colorHex;

    @Column(length = 40)
    private String capacity;

    @Column(length = 40)
    private String ram;

    /** Écart de prix en FCFA par rapport au prix de base du produit (peut être négatif). */
    @Column(name = "price_delta", nullable = false)
    private long priceDelta;

    @Column(nullable = false)
    private int stock;

    @Column(nullable = false)
    private int position;

    @Version
    private long version;

    public Long getId() { return id; }

    public Product getProduct() { return product; }
    void setProduct(Product product) { this.product = product; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getColorHex() { return colorHex; }
    public void setColorHex(String colorHex) { this.colorHex = colorHex; }

    public String getCapacity() { return capacity; }
    public void setCapacity(String capacity) { this.capacity = capacity; }

    public String getRam() { return ram; }
    public void setRam(String ram) { this.ram = ram; }

    public long getPriceDelta() { return priceDelta; }
    public void setPriceDelta(long priceDelta) { this.priceDelta = priceDelta; }

    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    /** Libellé lisible, ex. « Bleu · 256 Go · 8 Go ». */
    public String label() {
        List<String> parts = new ArrayList<>(3);
        if (color != null && !color.isBlank()) parts.add(color);
        if (capacity != null && !capacity.isBlank()) parts.add(capacity);
        if (ram != null && !ram.isBlank()) parts.add(ram);
        return String.join(" · ", parts);
    }
}
