/** Связанные показатели пищевой ценности. */
public record NutritionInfo(int weightGrams, int calories) {
    public NutritionInfo {
        if (weightGrams <= 0 || calories < 0) throw new IllegalArgumentException("Некорректная пищевая ценность");
    }
}
