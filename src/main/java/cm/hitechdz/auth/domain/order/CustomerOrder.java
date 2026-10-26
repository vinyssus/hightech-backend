package cm.hitechdz.auth.domain.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Commande. Les articles et l'adresse sont des copies figées au moment de l'achat.
 * stockReserved / soldCounted garantissent qu'une réservation n'est restituée et
 * qu'une vente n'est comptée qu'une seule fois.
 */
@Entity
@Table(name = "orders")
public class CustomerOrder {

    @Id
    private UUID id = UUID.randomUUID();

    /** Référence publique opaque (ex. HD-7K2M9QXA). */
    @Column(unique = true, length = 24)
    private String number;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private OrderStatus status = OrderStatus.PENDING_CONFIRMATION;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "payment_method", length = 30)
    private PaymentMethod paymentMethod;

    @Column(nullable = false, length = 3)
    private String currency = "XAF";

    @Column(nullable = false)
    private long subtotal;

    @Column(nullable = false)
    private long discount;

    @Column(nullable = false)
    private long shipping;

    @Column(nullable = false)
    private long total;

    @Column(name = "promo_code", length = 40)
    private String promoCode;

    /** Fin de la réservation de stock d'une commande à payer en ligne. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "delivery_name", length = 80) private String deliveryName;
    @Column(name = "delivery_phone", length = 20) private String deliveryPhone;
    @Column(name = "delivery_email", length = 254) private String deliveryEmail;
    @Column(name = "delivery_zone_id", length = 40) private String deliveryZoneId;
    @Column(name = "delivery_zone_label", length = 80) private String deliveryZoneLabel;
    @Column(name = "delivery_city", length = 80) private String deliveryCity;
    @Column(name = "delivery_address", length = 255) private String deliveryAddress;
    @Column(name = "delivery_notes", length = 500) private String deliveryNotes;

    @Column(name = "guest_token_hash", length = 64) private String guestTokenHash;
    @Column(name = "guest_token_expires_at") private Instant guestTokenExpiresAt;

    @Column(name = "idempotency_key", unique = true, length = 80) private String idempotencyKey;
    @Column(name = "request_hash", length = 64) private String requestHash;

    @Column(name = "stock_reserved", nullable = false) private boolean stockReserved;
    @Column(name = "sold_counted", nullable = false) private boolean soldCounted;

    @Column(name = "collected_at") private Instant collectedAt;
    @Column(name = "collected_by") private UUID collectedBy;
    @Column(name = "collected_method", length = 20) private String collectedMethod;
    @Column(name = "collected_reference", length = 120) private String collectedReference;
    @Column(name = "collected_amount") private Long collectedAmount;
    @Column(name = "collect_idempotency_key", length = 80) private String collectIdempotencyKey;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderEvent> events = new ArrayList<>();

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }

    public void addEvent(OrderEvent event) {
        event.setOrder(this);
        events.add(event);
    }

    public boolean isPickup() {
        return "retrait".equals(deliveryZoneId);
    }

    // ------------------------------------------------------------------ accesseurs
    public UUID getId() { return id; }

    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getCurrency() { return currency; }

    public long getSubtotal() { return subtotal; }
    public void setSubtotal(long subtotal) { this.subtotal = subtotal; }

    public long getDiscount() { return discount; }
    public void setDiscount(long discount) { this.discount = discount; }

    public long getShipping() { return shipping; }
    public void setShipping(long shipping) { this.shipping = shipping; }

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }

    public String getPromoCode() { return promoCode; }
    public void setPromoCode(String promoCode) { this.promoCode = promoCode; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public String getDeliveryName() { return deliveryName; }
    public void setDeliveryName(String v) { this.deliveryName = v; }
    public String getDeliveryPhone() { return deliveryPhone; }
    public void setDeliveryPhone(String v) { this.deliveryPhone = v; }
    public String getDeliveryEmail() { return deliveryEmail; }
    public void setDeliveryEmail(String v) { this.deliveryEmail = v; }
    public String getDeliveryZoneId() { return deliveryZoneId; }
    public void setDeliveryZoneId(String v) { this.deliveryZoneId = v; }
    public String getDeliveryZoneLabel() { return deliveryZoneLabel; }
    public void setDeliveryZoneLabel(String v) { this.deliveryZoneLabel = v; }
    public String getDeliveryCity() { return deliveryCity; }
    public void setDeliveryCity(String v) { this.deliveryCity = v; }
    public String getDeliveryAddress() { return deliveryAddress; }
    public void setDeliveryAddress(String v) { this.deliveryAddress = v; }
    public String getDeliveryNotes() { return deliveryNotes; }
    public void setDeliveryNotes(String v) { this.deliveryNotes = v; }

    public String getGuestTokenHash() { return guestTokenHash; }
    public void setGuestTokenHash(String v) { this.guestTokenHash = v; }
    public Instant getGuestTokenExpiresAt() { return guestTokenExpiresAt; }
    public void setGuestTokenExpiresAt(Instant v) { this.guestTokenExpiresAt = v; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String v) { this.requestHash = v; }

    public boolean isStockReserved() { return stockReserved; }
    public void setStockReserved(boolean v) { this.stockReserved = v; }
    public boolean isSoldCounted() { return soldCounted; }
    public void setSoldCounted(boolean v) { this.soldCounted = v; }

    public Instant getCollectedAt() { return collectedAt; }
    public UUID getCollectedBy() { return collectedBy; }
    public String getCollectedMethod() { return collectedMethod; }
    public String getCollectedReference() { return collectedReference; }
    public Long getCollectedAmount() { return collectedAmount; }
    public String getCollectIdempotencyKey() { return collectIdempotencyKey; }

    public void recordCollection(UUID adminId, String method, String reference, long amount, String key) {
        this.collectedAt = Instant.now();
        this.collectedBy = adminId;
        this.collectedMethod = method;
        this.collectedReference = reference;
        this.collectedAmount = amount;
        this.collectIdempotencyKey = key;
    }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<OrderItem> getItems() { return items; }
    public List<OrderEvent> getEvents() { return events; }
}
