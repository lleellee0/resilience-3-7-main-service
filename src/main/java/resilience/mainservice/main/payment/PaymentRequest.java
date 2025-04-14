package resilience.mainservice.main.payment;

// JSON 직렬화에 setter 필요함
public class PaymentRequest {
    private String orderId;
    private Double amount;

    // Getters & Setters
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }
}