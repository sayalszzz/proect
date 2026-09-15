import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Исправленный JavaFX-интерфейс. Все обращения к SQLite выполняются в фоновых Task. */
public final class RestaurantApp extends Application {
    private RestaurantController controller;

    private final ListView<String> menu = new ListView<>();
    private final ListView<String> cart = new ListView<>();
    private final ListView<String> history = new ListView<>();
    private final TextField customerName = new TextField("Анна");
    private final TextField customerPhone = new TextField("+79990000000");
    private final TextField search = new TextField();
    private final ComboBox<String> category = new ComboBox<>();
    private final ComboBox<OrderStatus> status = new ComboBox<>();
    private final Label total = new Label("Стоимость заказа: 0.00 руб.");
    private final Label revenue = new Label("Выручка: 0.00 руб.");
    private final Label loading = new Label();
    private final Label menuPageLabel = new Label("Страница 1 из 1");
    private final Label orderPageLabel = new Label("Страница 1 из 1");
    private final Button previousMenu = new Button("Назад");
    private final Button nextMenu = new Button("Далее");
    private final Button previousOrders = new Button("Назад");
    private final Button nextOrders = new Button("Далее");

    private final List<Dish> cartDishes = new ArrayList<>();
    private final Map<MenuKey, RestaurantController.DishPage> menuCache = new HashMap<>();
    private List<Dish> visibleDishes = List.of();
    private List<Order> visibleOrders = List.of();
    private int menuPageIndex;
    private int orderPageIndex;
    private int menuRequestVersion;
    private int activeTasks;
    private boolean updatingCategory;

    @Override
    public void start(Stage stage) {
        configureControls();

        VBox left = new VBox(8,
                new Label("Меню"),
                new HBox(6, search, category),
                menu,
                new HBox(6, previousMenu, menuPageLabel, nextMenu),
                dishForm()
        );
        left.setPrefWidth(410);

        Button addToCart = button("Добавить выбранные блюда", event -> addSelectedDishes());
        Button removeFromCart = button("Удалить выбранную позицию", event -> removeSelectedDish());
        Button clearCart = button("Очистить заказ", event -> clearCart());
        Button createOrder = button("Оформить заказ", event -> createOrder());
        VBox center = new VBox(8,
                new Label("Данные клиента"), customerName, customerPhone,
                new Label("Текущий заказ"), cart, total,
                addToCart, removeFromCart, clearCart, createOrder
        );
        center.setPrefWidth(410);

        Button changeStatus = button("Изменить статус", event -> changeSelectedOrderStatus());
        VBox right = new VBox(8,
                new Label("История заказов"), history,
                new HBox(6, previousOrders, orderPageLabel, nextOrders),
                new HBox(6, status, changeStatus),
                revenue, loading
        );
        right.setPrefWidth(380);

        BorderPane root = new BorderPane(new HBox(14, left, center, right));
        root.setPadding(new Insets(16));
        root.setTop(new Label("Ресторан  Управление меню и заказами"));
        BorderPane.setMargin(root.getTop(), new Insets(0, 0, 12, 0));

        stage.setTitle("Ресторан");
        stage.setScene(new Scene(root, 1260, 650));
        stage.show();

        initializeData();
    }

    private void configureControls() {
        menu.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        status.setItems(FXCollections.observableArrayList(OrderStatus.values()));
        status.setValue(OrderStatus.NEW);
        search.setPromptText("Поиск блюда");

        search.textProperty().addListener((observable, oldValue, newValue) -> {
            menuPageIndex = 0;
            loadMenuPage();
        });
        category.setOnAction(event -> {
            if (!updatingCategory) {
                menuPageIndex = 0;
                loadMenuPage();
            }
        });
        history.getSelectionModel().selectedIndexProperty().addListener((observable, oldValue, newValue) -> {
            int index = newValue.intValue();
            if (index >= 0 && index < visibleOrders.size()) status.setValue(visibleOrders.get(index).status());
        });

        previousMenu.setOnAction(event -> {
            if (menuPageIndex > 0) {
                menuPageIndex--;
                loadMenuPage();
            }
        });
        nextMenu.setOnAction(event -> {
            menuPageIndex++;
            loadMenuPage();
        });
        previousOrders.setOnAction(event -> {
            if (orderPageIndex > 0) {
                orderPageIndex--;
                loadOrdersAndRevenue();
            }
        });
        nextOrders.setOnAction(event -> {
            orderPageIndex++;
            loadOrdersAndRevenue();
        });
    }

    private void initializeData() {
        runDb(() -> {
            RestaurantController newController = new RestaurantController(new RestaurantDAO());
            newController.addDefaultDishesWhenMenuIsEmpty();
            controller = newController;
            return new InitialData(
                    newController.getCategories(),
                    newController.findDishes("", "Все", 0),
                    newController.getOrders(0),
                    newController.getDailyRevenue()
            );
        }, data -> {
            setCategories(data.categories());
            applyMenuPage(data.dishes());
            applyOrderPage(data.orders());
            revenue.setText("Выручка: " + money(data.revenue()) + " руб.");
        });
    }

    private GridPane dishForm() {
        TextField dishName = new TextField();
        TextField dishCategory = new TextField();
        TextField dishPrice = new TextField();
        dishName.setPromptText("Название");
        dishCategory.setPromptText("Категория");
        dishPrice.setPromptText("Цена");
        Button addDish = button("Добавить блюдо", event -> {
            BigDecimal price;
            try {
                price = new BigDecimal(dishPrice.getText().strip());
            } catch (RuntimeException exception) {
                warn("Цена должна быть числом.");
                return;
            }
            String name = dishName.getText();
            String categoryName = dishCategory.getText();
            runDb(() -> {
                controller.addDish(name, categoryName, price);
                return controller.getCategories();
            }, categories -> {
                dishName.clear();
                dishCategory.clear();
                dishPrice.clear();
                menuCache.clear();
                setCategories(categories);
                menuPageIndex = 0;
                loadMenuPage();
            });
        });

        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);
        grid.add(dishName, 0, 0);
        grid.add(dishCategory, 1, 0);
        grid.add(dishPrice, 0, 1);
        grid.add(addDish, 1, 1);
        return grid;
    }

    private void loadMenuPage() {
        if (controller == null) return;
        String query = search.getText() == null ? "" : search.getText().strip().toLowerCase();
        String selectedCategory = category.getValue() == null ? "Все" : category.getValue();
        MenuKey key = new MenuKey(query, selectedCategory, menuPageIndex);
        RestaurantController.DishPage cached = menuCache.get(key);
        if (cached != null) {
            applyMenuPage(cached);
            return;
        }
        int requestVersion = ++menuRequestVersion;
        runDb(() -> controller.findDishes(query, selectedCategory, menuPageIndex), page -> {
            if (requestVersion != menuRequestVersion) return;
            menuCache.put(key, page);
            applyMenuPage(page);
        });
    }

    private void applyMenuPage(RestaurantController.DishPage page) {
        if (page.page() > 0 && page.items().isEmpty()) {
            menuPageIndex--;
            loadMenuPage();
            return;
        }
        menuPageIndex = page.page();
        visibleDishes = page.items();
        menu.setItems(FXCollections.observableArrayList(visibleDishes.stream().map(this::formatDish).toList()));
        menuPageLabel.setText("Страница " + (page.page() + 1) + " из " + page.totalPages());
        previousMenu.setDisable(!page.hasPrevious());
        nextMenu.setDisable(!page.hasNext());
    }

    private void addSelectedDishes() {
        List<Integer> indexes = List.copyOf(menu.getSelectionModel().getSelectedIndices());
        if (indexes.isEmpty()) {
            warn("Выберите одно или несколько блюд.");
            return;
        }
        for (int index : indexes) {
            Dish dish = visibleDishes.get(index);
            boolean alreadyAdded = cartDishes.stream().anyMatch(item -> item.id() == dish.id());
            if (!alreadyAdded) cartDishes.add(dish);
        }
        menu.getSelectionModel().clearSelection();
        renderCart();
    }

    private void removeSelectedDish() {
        int index = cart.getSelectionModel().getSelectedIndex();
        if (index < 0) {
            warn("Выберите позицию текущего заказа.");
            return;
        }
        cartDishes.remove(index);
        renderCart();
    }

    private void clearCart() {
        cartDishes.clear();
        renderCart();
    }

    private void renderCart() {
        cart.setItems(FXCollections.observableArrayList(cartDishes.stream().map(this::formatDish).toList()));
        BigDecimal amount = cartDishes.stream().map(Dish::price).reduce(BigDecimal.ZERO, BigDecimal::add);
        total.setText("Стоимость заказа: " + money(amount) + " руб.");
    }

    private void createOrder() {
        if (cartDishes.isEmpty()) {
            warn("Добавьте блюда в текущий заказ.");
            return;
        }
        String name = customerName.getText();
        String phone = customerPhone.getText();
        List<Dish> items = List.copyOf(cartDishes);
        runDb(() -> controller.createOrder(new Customer(name, phone), items), order -> {
            cartDishes.clear();
            renderCart();
            orderPageIndex = 0;
            loadOrdersAndRevenue();
        });
    }

    private void loadOrdersAndRevenue() {
        if (controller == null) return;
        int requestedPage = orderPageIndex;
        runDb(() -> new OrderRefresh(controller.getOrders(requestedPage), controller.getDailyRevenue()), data -> {
            applyOrderPage(data.page());
            revenue.setText("Выручка: " + money(data.revenue()) + " руб.");
        });
    }

    private void applyOrderPage(RestaurantController.OrderPage page) {
        if (page.page() > 0 && page.items().isEmpty()) {
            orderPageIndex--;
            loadOrdersAndRevenue();
            return;
        }
        orderPageIndex = page.page();
        visibleOrders = page.items();
        history.setItems(FXCollections.observableArrayList(visibleOrders.stream().map(this::formatOrder).toList()));
        orderPageLabel.setText("Страница " + (page.page() + 1) + " из " + page.totalPages());
        previousOrders.setDisable(!page.hasPrevious());
        nextOrders.setDisable(!page.hasNext());
    }

    private void changeSelectedOrderStatus() {
        int index = history.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= visibleOrders.size()) {
            warn("Выберите заказ в истории.");
            return;
        }
        long orderId = visibleOrders.get(index).id();
        OrderStatus newStatus = status.getValue();
        runDb(() -> controller.changeOrderStatus(orderId, newStatus), order -> loadOrdersAndRevenue());
    }

    private void setCategories(List<String> categories) {
        String previous = category.getValue();
        List<String> values = new ArrayList<>();
        values.add("Все");
        values.addAll(categories);
        updatingCategory = true;
        category.setItems(FXCollections.observableArrayList(values));
        category.setValue(previous != null && values.contains(previous) ? previous : "Все");
        updatingCategory = false;
    }

    private <T> void runDb(Callable<T> operation, Consumer<T> onSuccess) {
        activeTasks++;
        loading.setText("Работа с базой...");
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return operation.call();
            }
        };
        task.setOnSucceeded(event -> {
            finishTask();
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            finishTask();
            Throwable error = task.getException();
            warn(error == null || error.getMessage() == null ? "Ошибка работы с SQLite." : error.getMessage());
        });
        Thread thread = new Thread(task, "sqlite-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private void finishTask() {
        activeTasks = Math.max(0, activeTasks - 1);
        loading.setText(activeTasks == 0 ? "" : "Работа с базой...");
    }

    private Button button(String text, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button button = new Button(text);
        button.setOnAction(handler);
        return button;
    }

    private String formatDish(Dish dish) {
        return dish.name() + " | " + dish.category() + " | " + money(dish.price()) + " руб.";
    }

    private String formatOrder(Order order) {
        return "№" + order.id() + " | " + order.customer().name() + " | " + order.status() + " | " + money(order.total()) + " руб.";
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void warn(String text) {
        new Alert(Alert.AlertType.WARNING, text).showAndWait();
    }

    private record MenuKey(String query, String category, int page) {}
    private record InitialData(List<String> categories, RestaurantController.DishPage dishes, RestaurantController.OrderPage orders, BigDecimal revenue) {}
    private record OrderRefresh(RestaurantController.OrderPage page, BigDecimal revenue) {}

    public static void main(String[] args) {
        launch(args);
    }
}
