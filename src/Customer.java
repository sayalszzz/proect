/** Контактные данные заказчика сгруппированы в отдельный объект. */
public record Customer(String name, String phone) {
    public Customer {
        if (name == null || name.isBlank() || phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("Имя и телефон обязательны");
        }
    }
}
