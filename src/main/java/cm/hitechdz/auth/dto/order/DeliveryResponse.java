package cm.hitechdz.auth.dto.order;

public record DeliveryResponse(String name, String phone, String email, String zoneId, String zoneLabel,
                               String city, String address, String notes) {}
