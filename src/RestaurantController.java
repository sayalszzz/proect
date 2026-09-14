import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class RestaurantController {
    private final RestaurantDAO dao;

    public RestaurantController(RestaurantDAO dao) { this.dao = dao; }

    public void addDish(Dish dish) { dao.addDish(dish); }

    public List<Dish> getAllDishes() { return dao.getAllDishes(); }
    public List<Order> getAllOrders() { return dao.getAllOrders(); }

    public Order createOrder(long id, Customer customer, List<Dish> dishes) {
        Order order = new Order(id, customer, dishes, OrderStatus.NEW, LocalDateTime.now());
        dao.addOrder(order);
        return order;
    }

    public Order changeOrderStatus(long orderId, OrderStatus status) {
        Order order = dao.getAllOrders().stream()
                .filter(item -> item.id() == orderId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Заказ не найден"));
        Order updatedOrder = order.withStatus(status);
        dao.replaceOrder(updatedOrder);
        return updatedOrder;
    }

    public Order addDishesToOrder(long orderId, List<Dish> newDishes) {
        Order order = dao.getAllOrders().stream().filter(item -> item.id() == orderId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Заказ не найден"));
        List<Dish> additions = List.copyOf(newDishes);
        dao.appendDishesToOrder(orderId, additions);
        List<Dish> allDishes = new java.util.ArrayList<>(order.dishes());
        allDishes.addAll(additions);
        return new Order(order.id(), order.customer(), allDishes, order.status(), order.createdAt());
    }

    public Order removeDishFromOrder(long orderId, long dishId) {
        Order order = dao.getAllOrders().stream().filter(item -> item.id() == orderId).findFirst().orElseThrow(() -> new IllegalArgumentException("Заказ не найден"));
        dao.removeDishFromOrder(orderId, dishId);
        List<Dish> dishes = new java.util.ArrayList<>(order.dishes());
        for (int index = 0; index < dishes.size(); index++) {
            if (dishes.get(index).id() == dishId) { dishes.remove(index); break; }
        }
        if (dishes.isEmpty()) { dao.deleteOrder(orderId); throw new IllegalStateException("Заказ пуст: он удален"); }
        return new Order(order.id(), order.customer(), dishes, order.status(), order.createdAt());
    }

    public void deleteOrder(long orderId) { dao.deleteOrder(orderId); }

    public List<Dish> filterByCategory(String category) {
        return dao.getAllDishes().stream().filter(d -> d.category().equalsIgnoreCase(category)).toList();
    }

    public List<Dish> searchDish(String query) {
        String normalizedQuery = query == null ? "" : query.toLowerCase();
        return dao.getAllDishes().stream().filter(d -> d.name().toLowerCase().contains(normalizedQuery)).toList();
    }

    public BigDecimal getDailyRevenue() {
        return dao.getAllOrders().stream().map(Order::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
