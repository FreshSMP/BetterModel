package kr.toxicity.model.api.data.raw;

import com.google.gson.*;
import kr.toxicity.model.api.util.MathUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Type;
import java.util.function.Function;

/**
 * A three float value (origin, rotation)
 * @param x x
 * @param y y
 * @param z z
 */
@ApiStatus.Internal
public record Float3(
        float x,
        float y,
        float z
) {

    /**
     * Creates floats
     * @param value scala
     */
    public Float3(float value) {
        this(value, value, value);
    }
    /**
     * Center
     */
    public static final Float3 CENTER = new Float3(8, 8, 8);
    /**
     * Zero
     */
    public static final Float3 ZERO = new Float3(0, 0, 0);

    /**
     * Parser
     */
    public static final Parser PARSER = new Parser();

    public static final class Parser implements Function<JsonElement, Float3>, JsonDeserializer<Float3> {
        private Parser() {
        }

        @Override
        public Float3 deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return apply(json);
        }

        @Override
        public Float3 apply(JsonElement element) {
            if (element == null || element.isJsonNull()) return ZERO;
            var array = element.getAsJsonArray();
            return new Float3(
                    array.get(0).getAsFloat(),
                    array.get(1).getAsFloat(),
                    array.get(2).getAsFloat()
            );
        }
    }

    /**
     * Adds other floats.
     * @param other other floats
     * @return new floats
     */
    public @NotNull Float3 plus(@NotNull Float3 other) {
        return new Float3(
                x + other.x,
                y + other.y,
                z + other.z
        );
    }

    /**
     * Converts zxy euler to xyz euler (Minecraft)
     * @return new float
     */
    public @NotNull Float3 convertToMinecraftDegree() {
        var vec = MathUtil.toXYZEuler(toVector());
        return new Float3(vec.x, vec.y, vec.z);
    }

    /**
     * Rotates this float
     * @param quaternionf rotation
     * @return new float
     */
    public @NotNull Float3 rotate(@NotNull Quaternionf quaternionf) {
        var vec = toVector().rotate(quaternionf);
        return new Float3(vec.x, vec.y, vec.z);
    }


    /**
     * Subtracts other floats.
     * @param other other floats
     * @return new floats
     */
    public @NotNull Float3 minus(@NotNull Float3 other) {
        return new Float3(
                x - other.x,
                y - other.y,
                z - other.z
        );
    }

    /**
     * Converts item model scale to block scale
     * @return block
     */
    public @NotNull Float3 toBlockScale() {
        return div(MathUtil.MODEL_TO_BLOCK_MULTIPLIER);
    }

    /**
     * Multiplies floats.
     * @param value multiplier
     * @return new floats
     */
    public @NotNull Float3 times(float value) {
        return new Float3(
                x * value,
                y * value,
                z * value
        );
    }
    /**
     * Divides floats.
     * @param value multiplier
     * @return new floats
     */
    public @NotNull Float3 div(float value) {
        return new Float3(
                x / value,
                y / value,
                z / value
        );
    }

    /**
     * Inverts XZ
     * @return new floats
     */
    public @NotNull Float3 invertXZ() {
        return new Float3(
                -x,
                y,
                -z
        );
    }

    /**
     * Converts floats to JSON array.
     * @return json array
     */
    public @NotNull JsonArray toJson() {
        var array = new JsonArray(3);
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    @Override
    public @NotNull String toString() {
        return toJson().toString();
    }

    /**
     * Converts floats to vector.
     * @return vector
     */
    public @NotNull Vector3f toVector() {
        return new Vector3f(x, y, z);
    }
}
