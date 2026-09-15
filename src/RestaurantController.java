import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Прикладные операции отделены от JavaFX и деталей JDBC. */
public final class RestaurantController {
    public static final int PAGE_SIZE = 10;

    private final RestaurantDAO dao;

    public RestaurantController(RestaurantDAO dao) {
        this.dao = dao;
    }

    public void addDish(String name, String category, BigDecimal price) {
        Dish dish = new Dish(dao.nextDishId(), name, category, price, new NutritionInfo(250, 200));
        dao.addDish(dish);
    }

    public void addDefaultDishesWhenMenuIsEmpty() {
        if (dao.countDishes("", "Все") != 0) return;
        dao.addDish(new Dish(1, "Том ям", "Супы", new BigDecimal("420.00"), new NutritionInfo(350, 310)));
        dao.addDish(new Dish(2, "Чай", "Напитки", new BigDecimal("120.00"), new NutritionInfo(300, 5)));
        dao.addDish(new Dish(3, "Чизкейк", "Десерты", new BigDecimal("260.00"), new NutritionInfo(150, 420)));
    }

    public DishPage findDishes(String query, String category, int page) {
        int safePage = Math.max(0, page);
        int offset = safePage * PAGE_SIZE;
        List<Dish> dishes = dao.findDishes(query, category, PAGE_SIZE, offset);
        long total = dao.countDishes(query, category);
        return new DishPage(dishes, safePage, total, PAGE_SIZE);
    }

    public List<String> getCategories() {
        return dao.getCategories();
    }

    public Order createOrder(Customer customer, List<Dish> dishes) {
        Order order = new Order(dao.nextOrderId(), customer, dishes, OrderStatus.NEW, LocalDateTime.now());
        dao.addOrder(order);
        return order;
    }

    public OrderPage getOrders(int page) {
        int safePage = Math.max(0, page);
        int offset = safePage * PAGE_SIZE;
        return new OrderPage(dao.getOrdersPage(PAGE_SIZE, offset), safePage, dao.countOrders(), PAGE_SIZE);
    }

    public Order changeOrderStatus(long orderId, OrderStatus status) {
        if (status == null) throw new IllegalArgumentException("Выберите статус");
        dao.updateOrderStatus(orderId, status);
        return dao.findOrderById(orderId);
    }

    public BigDecimal getDailyRevenue() {
        return dao.getRevenue();
    }

    public record DishPage(List<Dish> items, int page, long totalItems, int pageSize) {
        public int totalPages() { return Math.max(1, (int) Math.ceil((double) totalItems / pageSize)); }
        public boolean hasPrevious() { return page > 0; }
        public boolean hasNext() { return page + 1 < totalPages(); }
    }

    public record OrderPage(List<Order> items, int page, long totalItems, int pageSize) {
        public int totalPages() { return Math.max(1, (int) Math.ceil((double) totalItems / pageSize)); }
        public boolean hasPrevious() { return page > 0; }
        public boolean hasNext() { return page + 1 < totalPages(); }
    }
}
