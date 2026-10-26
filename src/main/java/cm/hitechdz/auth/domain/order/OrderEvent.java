package cm.hitechdz.auth.domain.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Étape de l'historique d'une commande (affichée dans la timeline). */
@Entity
@Table(name = "order_events")
public class OrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(length = 500)
    private String note;

    @Column(length = 80)
    private String actor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderEvent() {}

    public OrderEvent(OrderStatus status, String label, String note, String actor) {
        this.status = status;
        this.label = label;
        this.note = note;
        this.actor = actor;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    void setOrder(CustomerOrder order) { this.order = order; }

    public Long getId() { return id; }
    public OrderStatus getStatus() { return status; }
    public String getLabel() { return label; }
    public String getNote() { return note; }
    public String getActor() { return actor; }
    public Instant getCreatedAt() { return createdAt; }
}
