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

/** Image d'un produit. publicId est null pour une URL externe (non hébergée sur Cloudinary). */
@Entity
@Table(name = "product_images")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(name = "public_id", length = 255)
    private String publicId;

    /** 0 = image principale. */
    @Column(nullable = false)
    private int position;

    private Integer width;
    private Integer height;

    protected ProductImage() {}

    public ProductImage(String url, String publicId, Integer width, Integer height) {
        this.url = url;
        this.publicId = publicId;
        this.width = width;
        this.height = height;
    }

    public Long getId() { return id; }

    public Product getProduct() { return product; }
    void setProduct(Product product) { this.product = product; }

    public String getUrl() { return url; }
    public String getPublicId() { return publicId; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
}
