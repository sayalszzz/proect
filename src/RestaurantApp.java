import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class RestaurantApp extends Application {
    private RestaurantController controller;
    private List<Dish> dishes = List.of();
    private List<Order> orders = List.of();
    private final ListView<String> menu = new ListView<>(), current = new ListView<>(), history = new ListView<>();
    private final TextField customerName = new TextField("Анна"), customerPhone = new TextField("+79990000000"), search = new TextField();
    private final ComboBox<String> category = new ComboBox<>();
    private final ComboBox<OrderStatus> status = new ComboBox<>();
    private final Label total = new Label("Стоимость заказа: 0.00 руб."), currentStatus = new Label("Статус: заказ не создан"), revenue = new Label("Выручка: 0.00 руб."), loading = new Label();
    private Order selectedOrder;

    @Override public void start(Stage stage) {
        menu.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        status.setItems(FXCollections.observableArrayList(OrderStatus.values())); status.setValue(OrderStatus.NEW);
        search.setPromptText("Поиск блюда"); search.textProperty().addListener((o,a,b)->renderMenu()); category.setOnAction(e->renderMenu());
        Button addToOrder=button("Добавить выбранные в текущий заказ",e->addSelected());
        Button remove=button("Удалить выбранную позицию",e->removeSelected());
        Button clear=button("Очистить заказ",e->clearOrder());
        Button next=button("Оформить и создать новый заказ",e->{selectedOrder=null;renderCurrent();});
        Button change=button("Изменить статус",e->changeStatus());
        VBox left=new VBox(8,new Label("Меню"),new HBox(6,search,category),menu,dishForm()); left.setPrefWidth(400);
        VBox center=new VBox(8,new Label("Данные клиента"),customerName,customerPhone,new Label("Текущий заказ"),current,total,currentStatus,addToOrder,remove,clear,next,new HBox(6,status,change)); center.setPrefWidth(410);
        VBox right=new VBox(8,new Label("История заказов"),history,revenue,loading); right.setPrefWidth(290);
        BorderPane root=new BorderPane(new HBox(14,left,center,right)); root.setPadding(new Insets(16)); root.setTop(new Label("Ресторан  Управление меню и заказами"));
        stage.setTitle("Ресторан");stage.setScene(new Scene(root,1140,610));stage.show();
        runDb(()->{RestaurantDAO dao=new RestaurantDAO();controller=new RestaurantController(dao);if(controller.getAllDishes().isEmpty()){controller.addDish(new Dish(1,"Том ям","Супы",new BigDecimal("420.00"),new NutritionInfo(350,310)));controller.addDish(new Dish(2,"Чай","Напитки",new BigDecimal("120.00"),new NutritionInfo(300,5)));controller.addDish(new Dish(3,"Чизкейк","Десерты",new BigDecimal("260.00"),new NutritionInfo(150,420)));}return snapshot();},this::applySnapshot);
    }
    private Button button(String text,javafx.event.EventHandler<javafx.event.ActionEvent> handler){Button b=new Button(text);b.setOnAction(handler);return b;}
    private GridPane dishForm(){TextField n=new TextField(),c=new TextField(),p=new TextField();n.setPromptText("Название");c.setPromptText("Категория");p.setPromptText("Цена");Button add=button("Добавить блюдо",e->{try{long id=dishes.stream().mapToLong(Dish::id).max().orElse(0)+1;Dish dish=new Dish(id,n.getText(),c.getText(),new BigDecimal(p.getText()),new NutritionInfo(250,200));runDb(()->{controller.addDish(dish);return snapshot();},s->{n.clear();c.clear();p.clear();applySnapshot(s);});}catch(RuntimeException x){warn("Проверьте данные блюда.");}});GridPane g=new GridPane();g.setHgap(6);g.setVgap(6);g.add(n,0,0);g.add(c,1,0);g.add(p,0,1);g.add(add,1,1);return g;}
    private List<Dish> visible(){String q=search.getText().toLowerCase();String c=category.getValue();return dishes.stream().filter(d->d.name().toLowerCase().contains(q)).filter(d->c==null||c.equals("Все")||d.category().equalsIgnoreCase(c)).toList();}
    private void renderMenu(){List<Dish> shown=visible();menu.setItems(FXCollections.observableArrayList(shown.stream().map(d->d.name()+" | "+d.category()+" | "+d.price()+" руб.").toList()));}
    private void addSelected(){List<Integer> indexes=List.copyOf(menu.getSelectionModel().getSelectedIndices());if(indexes.isEmpty()){warn("Выберите блюдо.");return;}List<Dish> shown=visible(),chosen=indexes.stream().map(shown::get).toList();Order before=selectedOrder;String n=customerName.getText(),p=customerPhone.getText();runDb(()->before==null?controller.createOrder(orders.stream().mapToLong(Order::id).max().orElse(0)+1,new Customer(n,p),chosen):controller.addDishesToOrder(before.id(),chosen),o->{selectedOrder=o;renderCurrent();refresh();});}
    private void removeSelected(){int i=current.getSelectionModel().getSelectedIndex();if(selectedOrder==null||i<0){warn("Выберите позицию.");return;}long orderId=selectedOrder.id(),dishId=selectedOrder.dishes().get(i).id();runDb(()->controller.removeDishFromOrder(orderId,dishId),o->{selectedOrder=o;renderCurrent();refresh();});}
    private void clearOrder(){if(selectedOrder==null)return;long id=selectedOrder.id();runDb(()->{controller.deleteOrder(id);return null;},x->{selectedOrder=null;renderCurrent();refresh();});}
    private void changeStatus(){if(selectedOrder==null){warn("Сначала создайте заказ.");return;}long id=selectedOrder.id();OrderStatus s=status.getValue();runDb(()->controller.changeOrderStatus(id,s),o->{selectedOrder=o;renderCurrent();refresh();});}
    private Snapshot snapshot(){return new Snapshot(controller.getAllDishes(),controller.getAllOrders());}
    private void refresh(){runDb(this::snapshot,this::applySnapshot);}
    private void applySnapshot(Snapshot s){dishes=s.dishes;orders=s.orders;String old=category.getValue();List<String> cs=new ArrayList<>(dishes.stream().map(Dish::category).distinct().toList());cs.add(0,"Все");category.setItems(FXCollections.observableArrayList(cs));category.setValue(old!=null&&cs.contains(old)?old:"Все");renderMenu();history.setItems(FXCollections.observableArrayList(orders.stream().map(o->"№"+o.id()+" | "+o.customer().name()+" | "+o.status()+" | "+o.total()+" руб.").toList()));revenue.setText("Выручка: "+orders.stream().map(Order::total).reduce(BigDecimal.ZERO,BigDecimal::add)+" руб.");}
    private void renderCurrent(){if(selectedOrder==null){current.getItems().clear();total.setText("Стоимость заказа: 0.00 руб.");currentStatus.setText("Статус: заказ не создан");return;}current.setItems(FXCollections.observableArrayList(selectedOrder.dishes().stream().map(d->d.name()+" | "+d.price()+" руб.").toList()));total.setText("Стоимость заказа: "+selectedOrder.total()+" руб.");currentStatus.setText("Статус: "+selectedOrder.status());}
    private <T> void runDb(Callable<T> work,Consumer<T> done){loading.setText("Работа с базой...");Task<T> task=new Task<>(){@Override protected T call() throws Exception{return work.call();}};task.setOnSucceeded(e->{loading.setText("");done.accept(task.getValue());});task.setOnFailed(e->{loading.setText("");warn(task.getException().getMessage());});Thread thread=new Thread(task,"sqlite-worker");thread.setDaemon(true);thread.start();}
    private void warn(String text){new Alert(Alert.AlertType.WARNING,text==null?"Ошибка работы с БД":text).showAndWait();}
    private record Snapshot(List<Dish> dishes,List<Order> orders){}
    public static void main(String[] args){launch(args);}
}
