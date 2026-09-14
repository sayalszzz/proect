import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Заказ неизменяем; его итог вычисляется один раз из позиций. */
public final class Order {
    private final long id;
    private final Customer customer;
    private final List<Dish> dishes;
    private final OrderStatus status;
    private final LocalDateTime createdAt;
    private final BigDecimal total;

    public Order(long id, Customer customer, List<Dish> dishes, OrderStatus status, LocalDateTime createdAt) {
        if (id <= 0 || dishes == null || dishes.isEmpty()) throw new IllegalArgumentException("Заказ должен содержать блюдо");
        this.id = id;
        this.customer = Objects.requireNonNull(customer);
        this.dishes = List.copyOf(dishes);
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.total = dishes.stream().map(Dish::price).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public long id() { return id; }
    public Customer customer() { return customer; }
    public List<Dish> dishes() { return dishes; }
    public OrderStatus status() { return status; }
    public LocalDateTime createdAt() { return createdAt; }
    public BigDecimal total() { return total; }

    public Order withStatus(OrderStatus newStatus) {
        return new Order(id, customer, dishes, newStatus, createdAt);
    }
}
