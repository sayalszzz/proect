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

/** DAO с SQLite, параметризованными запросами и транзакцией сохранения заказа. */
public final class RestaurantDAO {
    private static final String URL = "jdbc:sqlite:restaurant.db";

    public RestaurantDAO() { initializeSchema(); }
    private Connection connect() throws SQLException { return DriverManager.getConnection(URL); }

    private void initializeSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS dishes (id INTEGER PRIMARY KEY, name TEXT NOT NULL, category TEXT NOT NULL, price TEXT NOT NULL, weight INTEGER NOT NULL, calories INTEGER NOT NULL);"
                + "CREATE TABLE IF NOT EXISTS orders (id INTEGER PRIMARY KEY, customer_name TEXT NOT NULL, customer_phone TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL);"
                + "CREATE TABLE IF NOT EXISTS order_items (id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL, dish_id INTEGER NOT NULL, FOREIGN KEY(order_id) REFERENCES orders(id), FOREIGN KEY(dish_id) REFERENCES dishes(id));"
                + "CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id);"
                + "CREATE INDEX IF NOT EXISTS idx_dishes_category ON dishes(category);";
        try (Connection connection = connect(); Statement statement = connection.createStatement()) { statement.executeUpdate(sql); }
        catch (SQLException exception) { throw new IllegalStateException("Не удалось создать таблицы SQLite", exception); }
    }

    public void resetDatabase() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS order_items"); statement.executeUpdate("DROP TABLE IF EXISTS orders"); statement.executeUpdate("DROP TABLE IF EXISTS dishes");
            initializeSchema();
        } catch (SQLException exception) { throw new IllegalStateException("Не удалось очистить БД", exception); }
    }

    public void addDish(Dish dish) {
        String sql = "INSERT INTO dishes(id, name, category, price, weight, calories) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, dish.id()); statement.setString(2, dish.name()); statement.setString(3, dish.category()); statement.setString(4, dish.price().toPlainString());
            statement.setInt(5, dish.nutrition().weightGrams()); statement.setInt(6, dish.nutrition().calories()); statement.executeUpdate();
        } catch (SQLException exception) { throw new IllegalStateException("Не удалось добавить блюдо", exception); }
    }

    public List<Dish> getAllDishes() {
        List<Dish> dishes = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM dishes ORDER BY id"); ResultSet rs = statement.executeQuery()) {
            while (rs.next()) dishes.add(mapDish(rs));
            return dishes;
        } catch (SQLException exception) { throw new IllegalStateException("Не удалось загрузить меню", exception); }
    }

    public void addOrder(Order order) {
        String orderSql = "INSERT INTO orders(id, customer_name, customer_phone, status, created_at) VALUES (?, ?, ?, ?, ?)";
        String itemSql = "INSERT INTO order_items(order_id, dish_id) VALUES (?, ?)";
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement orderStatement = connection.prepareStatement(orderSql); PreparedStatement itemStatement = connection.prepareStatement(itemSql)) {
                orderStatement.setLong(1, order.id()); orderStatement.setString(2, order.customer().name()); orderStatement.setString(3, order.customer().phone()); orderStatement.setString(4, order.status().name()); orderStatement.setString(5, order.createdAt().toString()); orderStatement.executeUpdate();
                for (Dish dish : order.dishes()) { itemStatement.setLong(1, order.id()); itemStatement.setLong(2, dish.id()); itemStatement.addBatch(); }
                itemStatement.executeBatch(); connection.commit();
            } catch (SQLException exception) { connection.rollback(); throw exception; }
        } catch (SQLException exception) { throw new IllegalStateException("Не удалось сохранить заказ", exception); }
    }

    public List<Order> getAllOrders() {
        String sql = "SELECT o.id order_id, o.customer_name, o.customer_phone, o.status, o.created_at, d.id dish_id, d.name, d.category, d.price, d.weight, d.calories "
                + "FROM orders o JOIN order_items oi ON oi.order_id = o.id JOIN dishes d ON d.id = oi.dish_id ORDER BY o.id, d.id";
        Map<Long, OrderData> grouped = new LinkedHashMap<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("order_id");
                OrderData data = grouped.computeIfAbsent(id, ignored -> new OrderData(id, rsString(rs, "customer_name"), rsString(rs, "customer_phone"), rsString(rs, "status"), rsString(rs, "created_at")));
                data.dishes.add(new Dish(rs.getLong("dish_id"), rs.getString("name"), rs.getString("category"), new BigDecimal(rs.getString("price")), new NutritionInfo(rs.getInt("weight"), rs.getInt("calories"))));
            }
        } catch (SQLException exception) { throw new IllegalStateException("Не удалось загрузить заказы", exception); }
        return grouped.values().stream().map(data -> new Order(data.id, new Customer(data.customerName, data.customerPhone), data.dishes, OrderStatus.valueOf(data.status), LocalDateTime.parse(data.createdAt))).toList();
    }

    public void replaceOrder(Order order) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("UPDATE orders SET status = ? WHERE id = ?")) {
            statement.setString(1, order.status().name()); statement.setLong(2, order.id());
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Заказ не найден");
        } catch (SQLException exception) { throw new IllegalStateException("Не удалось обновить статус", exception); }
    }

    public void appendDishesToOrder(long orderId, List<Dish> dishes) {
        String sql = "INSERT INTO order_items(order_id, dish_id) VALUES (?, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Dish dish : dishes) { statement.setLong(1, orderId); statement.setLong(2, dish.id()); statement.addBatch(); }
            statement.executeBatch();
        } catch (SQLException exception) { throw new IllegalStateException("Не удалось добавить блюда в заказ", exception); }
    }

    public void removeDishFromOrder(long orderId, long dishId) {
        String sql = "DELETE FROM order_items WHERE id = (SELECT id FROM order_items WHERE order_id = ? AND dish_id = ? LIMIT 1)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, orderId); statement.setLong(2, dishId); statement.executeUpdate();
        } catch (SQLException exception) { throw new IllegalStateException("Не удалось удалить блюдо из заказа", exception); }
    }

    public void deleteOrder(long orderId) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement items = connection.prepareStatement("DELETE FROM order_items WHERE order_id = ?"); PreparedStatement order = connection.prepareStatement("DELETE FROM orders WHERE id = ?")) {
                items.setLong(1, orderId); items.executeUpdate(); order.setLong(1, orderId); order.executeUpdate(); connection.commit();
            } catch (SQLException exception) { connection.rollback(); throw exception; }
        } catch (SQLException exception) { throw new IllegalStateException("Не удалось удалить заказ", exception); }
    }

    private static String rsString(ResultSet rs, String column) { try { return rs.getString(column); } catch (SQLException exception) { throw new IllegalStateException(exception); } }
    private Dish mapDish(ResultSet rs) throws SQLException { return new Dish(rs.getLong("id"), rs.getString("name"), rs.getString("category"), new BigDecimal(rs.getString("price")), new NutritionInfo(rs.getInt("weight"), rs.getInt("calories"))); }
    private static final class OrderData { final long id; final String customerName, customerPhone, status, createdAt; final List<Dish> dishes = new ArrayList<>(); OrderData(long id, String customerName, String customerPhone, String status, String createdAt) { this.id=id; this.customerName=customerName; this.customerPhone=customerPhone; this.status=status; this.createdAt=createdAt; } }
}
