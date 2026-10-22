package cm.hitechdz.auth.domain.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Ligne de commande : copie figée du produit au moment de l'achat.
 * Elle ne change pas si le produit est modifié ou supprimé ensuite.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(length = 80)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "variant_label", length = 120)
    private String variantLabel;

    @Column(length = 500)
    private String image;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int qty;

    protected OrderItem() {}

    public OrderItem(String productId, String sku, String name, String variantLabel,
                     String image, long unitPrice, int qty) {
        this.productId = productId;
        this.sku = sku;
        this.name = name;
        this.variantLabel = variantLabel;
        this.image = image;
        this.unitPrice = unitPrice;
        this.qty = qty;
    }

    public Long getId() { return id; }

    public CustomerOrder getOrder() { return order; }
    void setOrder(CustomerOrder order) { this.order = order; }

    public String getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getVariantLabel() { return variantLabel; }
    public String getImage() { return image; }
    public long getUnitPrice() { return unitPrice; }
    public int getQty() { return qty; }
}
