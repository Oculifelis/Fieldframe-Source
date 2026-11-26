package dev.fieldframe.core.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.fieldframe.api.id.FfId;

/**
 * Readable recipe JSON builders for scripts, producing the documented Fieldframe recipe schema. Scripts pass the
 * result to their recipe event's {@code custom(...)} method, so the platform's own recipe pipeline validates and
 * registers it. The JSON shape is edition-specific only in the item/result key, chosen by {@code resultKey}.
 */
public final class ScriptRecipes {
    private final String resultKey;
    private final boolean stringIngredients;

    public ScriptRecipes(String resultKey, boolean stringIngredients) {
        this.resultKey = resultKey;
        this.stringIngredients = stringIngredients;
    }

    public Builder process(String family) {
        FfId f = FfId.tryParse(family);
        if (f == null) {
            throw new IllegalArgumentException("invalid family '" + family + "'");
        }
        return new Builder(f);
    }

    public final class Builder {
        private final JsonObject json = new JsonObject();
        private final JsonArray inputs = new JsonArray();
        private final JsonArray results = new JsonArray();

        Builder(FfId family) {
            boolean builtin = family.namespace().equals("fieldframe") && java.util.Set.of("heating", "crushing", "pressing", "mixing", "washing").contains(family.path());
            json.addProperty("type", builtin ? family.toString() : "fieldframe:process");
            if (!builtin) {
                json.addProperty("family", family.toString());
            }
            json.add("inputs", inputs);
            json.add("results", results);
            json.addProperty("duration", 100);
            json.addProperty("energy", 20);
        }

        private com.google.gson.JsonElement ingredient(String id) {
            if (stringIngredients) {
                return new com.google.gson.JsonPrimitive(id);
            }
            JsonObject o = new JsonObject();
            if (id.startsWith("#")) {
                o.addProperty("tag", id.substring(1));
            } else {
                o.addProperty("item", id);
            }
            return o;
        }

        public Builder input(String item, double count) {
            JsonObject o = new JsonObject();
            o.add("ingredient", ingredient(item));
            o.addProperty("count", (int) count);
            inputs.add(o);
            return this;
        }

        public Builder catalyst(String item) {
            JsonObject o = new JsonObject();
            o.add("ingredient", ingredient(item));
            o.addProperty("count", 1);
            o.addProperty("catalyst", true);
            inputs.add(o);
            return this;
        }

        public Builder fluidInput(String fluid, double amount) {
            JsonObject o = new JsonObject();
            if (fluid.startsWith("#")) {
                o.addProperty("tag", fluid.substring(1));
            } else {
                o.addProperty("fluid", fluid);
            }
            o.addProperty("amount", (int) amount);
            json.add("fluid_input", o);
            return this;
        }

        public Builder result(String item, double count) {
            return result(item, count, 1.0);
        }

        public Builder result(String item, double count, double chance) {
            JsonObject o = new JsonObject();
            o.addProperty(resultKey, item);
            o.addProperty("count", (int) count);
            if (chance < 1) {
                o.addProperty("chance", chance);
            }
            results.add(o);
            return this;
        }

        public Builder fluidResult(String fluid, double amount) {
            JsonObject o = new JsonObject();
            o.addProperty("fluid", fluid);
            o.addProperty("amount", (int) amount);
            json.add("fluid_result", o);
            return this;
        }

        public Builder duration(double ticks) {
            json.addProperty("duration", (int) ticks);
            return this;
        }

        public Builder energy(double rfPerTick) {
            json.addProperty("energy", (int) rfPerTick);
            return this;
        }

        public JsonObject json() {
            return json.deepCopy();
        }
    }
}
