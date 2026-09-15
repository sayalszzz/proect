import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Доступ к SQLite: параметризованные запросы, транзакции, индексы и пагинация. */
public final class RestaurantDAO {
    private static final String URL = "jdbc:sqlite:restaurant.db";

    public RestaurantDAO() { initializeSchema(); }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(URL);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private void initializeSchema() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS dishes (id INTEGER PRIMARY KEY, name TEXT NOT NULL, category TEXT NOT NULL, price TEXT NOT NULL, weight INTEGER NOT NULL, calories INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS orders (id INTEGER PRIMARY KEY, customer_name TEXT NOT NULL, customer_phone TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS order_items (order_id INTEGER NOT NULL, dish_id INTEGER NOT NULL, PRIMARY KEY(order_id, dish_id), FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE CASCADE, FOREIGN KEY(dish_id) REFERENCES dishes(id))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dishes_category ON dishes(category COLLATE NOCASE)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dishes_name ON dishes(name COLLATE NOCASE)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders(created_at)");
        } catch (SQLException exception) {
            throw databaseError("Не удалось создать таблицы SQLite", exception);
        }
    }

    public void addDish(Dish dish) {
        String sql = "INSERT INTO dishes(id, name, category, price, weight, calories) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, dish.id());
            statement.setString(2, dish.name());
            statement.setString(3, dish.category());
            statement.setString(4, dish.price().toPlainString());
            statement.setInt(5, dish.nutrition().weightGrams());
            statement.setInt(6, dish.nutrition().calories());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseError("Не удалось добавить блюдо", exception);
        }
    }

    public List<Dish> findDishes(String query, String category, int limit, int offset) {
        validatePage(limit, offset);
        StringBuilder sql = new StringBuilder("SELECT id AS d_id, name AS d_name, category AS d_category, price AS d_price, weight AS d_weight, calories AS d_calories FROM dishes WHERE 1=1");
        List<String> parameters = appendDishFilters(sql, normalize(query), normalizeCategory(category));
        sql.append(" ORDER BY id LIMIT ? OFFSET ?");
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = bindStrings(statement, parameters);
            statement.setInt(index++, limit);
            statement.setInt(index, offset);
            try (ResultSet result = statement.executeQuery()) {
                List<Dish> dishes = new ArrayList<>();
                while (result.next()) dishes.add(mapDish(result, "d_"));
                return List.copyOf(dishes);
            }
        } catch (SQLException exception) {
            throw databaseError("Не удалось загрузить меню", exception);
        }
    }

    public long countDishes(String query, String category) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM dishes WHERE 1=1");
        List<String> parameters = appendDishFilters(sql, normalize(query), normalizeCategory(category));
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindStrings(statement, parameters);
            try (ResultSet result = statement.executeQuery()) { return result.getLong(1); }
        } catch (SQLException exception) {
            throw databaseError("Не удалось посчитать блюда", exception);
        }
    }

    public List<String> getCategories() {
        String sql = "SELECT DISTINCT category FROM dishes ORDER BY category COLLATE NOCASE";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            List<String> categories = new ArrayList<>();
            while (result.next()) categories.add(result.getString(1));
            return List.copyOf(categories);
        } catch (SQLException exception) {
            throw databaseError("Не удалось загрузить категории", exception);
        }
    }

    public long nextDishId() { return nextId("dishes"); }
    public long nextOrderId() { return nextId("orders"); }

    public void addOrder(Order order) {
        String orderSql = "INSERT INTO orders(id, customer_name, customer_phone, status, created_at) VALUES (?, ?, ?, ?, ?)";
        String itemSql = "INSERT INTO order_items(order_id, dish_id) VALUES (?, ?)";
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement orderStatement = connection.prepareStatement(orderSql); PreparedStatement itemStatement = connection.prepareStatement(itemSql)) {
                orderStatement.setLong(1, order.id());
                orderStatement.setString(2, order.customer().name());
                orderStatement.setString(3, order.customer().phone());
                orderStatement.setString(4, order.status().name());
                orderStatement.setString(5, order.createdAt().toString());
                orderStatement.executeUpdate();
                for (Dish dish : order.dishes()) {
                    itemStatement.setLong(1, order.id());
                    itemStatement.setLong(2, dish.id());
                    itemStatement.addBatch();
                }
                itemStatement.executeBatch();
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseError("Не удалось сохранить заказ", exception);
        }
    }

    public List<Order> getOrdersPage(int limit, int offset) {
        validatePage(limit, offset);
        String sql = orderSelect()
                + " JOIN (SELECT id FROM orders ORDER BY id DESC LIMIT ? OFFSET ?) page ON page.id = o.id"
                + " ORDER BY o.id DESC, d.id";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            try (ResultSet result = statement.executeQuery()) { return mapOrders(result); }
        } catch (SQLException exception) {
            throw databaseError("Не удалось загрузить заказы", exception);
        }
    }

    public long countOrders() {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM orders"); ResultSet result = statement.executeQuery()) {
            return result.getLong(1);
        } catch (SQLException exception) {
            throw databaseError("Не удалось посчитать заказы", exception);
        }
    }

    public Order findOrderById(long orderId) {
        String sql = orderSelect() + " WHERE o.id = ? ORDER BY d.id";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, orderId);
            try (ResultSet result = statement.executeQuery()) {
                List<Order> orders = mapOrders(result);
                if (orders.isEmpty()) throw new IllegalArgumentException("Заказ не найден");
                return orders.get(0);
            }
        } catch (SQLException exception) {
            throw databaseError("Не удалось загрузить заказ", exception);
        }
    }

    public void updateOrderStatus(long orderId, OrderStatus status) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("UPDATE orders SET status = ? WHERE id = ?")) {
            statement.setString(1, status.name());
            statement.setLong(2, orderId);
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Заказ не найден");
        } catch (SQLException exception) {
            throw databaseError("Не удалось обновить статус", exception);
        }
    }

    public BigDecimal getRevenue() {
        String sql = "SELECT COALESCE(SUM(CAST(d.price AS REAL)), 0) FROM order_items oi JOIN dishes d ON d.id = oi.dish_id";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            return new BigDecimal(result.getString(1)).setScale(2);
        } catch (SQLException exception) {
            throw databaseError("Не удалось рассчитать выручку", exception);
        }
    }

    private static List<String> appendDishFilters(StringBuilder sql, String query, String category) {
        List<String> parameters = new ArrayList<>();
        if (!query.isBlank()) {
            sql.append(" AND name LIKE ? COLLATE NOCASE");
            parameters.add("%" + query + "%");
        }
        if (category != null) {
            sql.append(" AND category = ? COLLATE NOCASE");
            parameters.add(category);
        }
        return parameters;
    }

    private static int bindStrings(PreparedStatement statement, List<String> parameters) throws SQLException {
        int index = 1;
        for (String parameter : parameters) statement.setString(index++, parameter);
        return index;
    }

    private static String orderSelect() {
        return "SELECT o.id AS order_id, o.customer_name, o.customer_phone, o.status, o.created_at, "
                + "d.id AS d_id, d.name AS d_name, d.category AS d_category, d.price AS d_price, d.weight AS d_weight, d.calories AS d_calories "
                + "FROM orders o JOIN order_items oi ON oi.order_id = o.id JOIN dishes d ON d.id = oi.dish_id";
    }

    private static List<Order> mapOrders(ResultSet result) throws SQLException {
        Map<Long, OrderData> grouped = new LinkedHashMap<>();
        while (result.next()) {
            long id = result.getLong("order_id");
            OrderData data = grouped.computeIfAbsent(id, ignored -> new OrderData(
                    id,
                    readString(result, "customer_name"),
                    readString(result, "customer_phone"),
                    readString(result, "status"),
                    readString(result, "created_at")
            ));
            data.dishes.add(mapDish(result, "d_"));
        }
        return grouped.values().stream().map(OrderData::toOrder).toList();
    }

    private static Dish mapDish(ResultSet result, String prefix) throws SQLException {
        return new Dish(
                result.getLong(prefix + "id"),
                result.getString(prefix + "name"),
                result.getString(prefix + "category"),
                new BigDecimal(result.getString(prefix + "price")),
                new NutritionInfo(result.getInt(prefix + "weight"), result.getInt(prefix + "calories"))
        );
    }

    private long nextId(String table) {
        if (!table.equals("dishes") && !table.equals("orders")) throw new IllegalArgumentException("Неизвестная таблица");
        String sql = "SELECT COALESCE(MAX(id), 0) + 1 FROM " + table;
        try (Connection connection = connect(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.getLong(1);
        } catch (SQLException exception) {
            throw databaseError("Не удалось сформировать идентификатор", exception);
        }
    }

    private static String normalize(String value) { return value == null ? "" : value.strip().toLowerCase(); }
    private static String normalizeCategory(String value) { return value == null || value.isBlank() || value.equals("Все") ? null : value.strip(); }

    private static void validatePage(int limit, int offset) {
        if (limit <= 0 || limit > 100 || offset < 0) throw new IllegalArgumentException("Некорректная страница");
    }

    private static String readString(ResultSet result, String column) {
        try {
            return result.getString(column);
        } catch (SQLException exception) {
            throw databaseError("Не удалось прочитать поле " + column, exception);
        }
    }

    private static IllegalStateException databaseError(String message, SQLException cause) {
        return new IllegalStateException(message, cause);
    }

    private static final class OrderData {
        private final long id;
        private final String customerName;
        private final String customerPhone;
        private final String status;
        private final String createdAt;
        private final List<Dish> dishes = new ArrayList<>();

        private OrderData(long id, String customerName, String customerPhone, String status, String createdAt) {
            this.id = id;
            this.customerName = customerName;
            this.customerPhone = customerPhone;
            this.status = status;
            this.createdAt = createdAt;
        }

        private Order toOrder() {
            return new Order(id, new Customer(customerName, customerPhone), dishes, OrderStatus.valueOf(status), LocalDateTime.parse(createdAt));
        }
    }
}
