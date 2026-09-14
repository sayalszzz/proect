import java.math.BigDecimal;
import java.util.Objects;

/** Блюдо в меню ресторана. */
public final class Dish {
    private final long id;
    private final String name;
    private final String category;
    private final BigDecimal price;
    private final NutritionInfo nutrition;

    public Dish(long id, String name, String category, BigDecimal price, NutritionInfo nutrition) {
        if (id <= 0 || name == null || name.isBlank() || category == null || category.isBlank()) {
            throw new IllegalArgumentException("Некорректные данные блюда");
        }
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("Цена не может быть отрицательной");
        }
        this.id = id;
        this.name = name;
        this.category = category;
        this.price = price;
        this.nutrition = Objects.requireNonNull(nutrition);
    }

    public long id() { return id; }
    public String name() { return name; }
    public String category() { return category; }
    public BigDecimal price() { return price; }
    public NutritionInfo nutrition() { return nutrition; }
}
