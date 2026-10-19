package dev.compat.emi;

import cc.abbie.emi_ores.compat.emi.EmiOresRecipeCategories;
import cc.abbie.emi_ores.compat.emi.stack.BiomeEmiStack;
import dev.OresAndDrillsMod;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import dev.world.level.levelgen.OreDepositOrePalette;
import dev.world.level.levelgen.OreGenerationSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.biome.Biome;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * EMI Ores lists every registered ore feature with the biomes that still carry it. Deposits replace those
 * features with unregistered ones, so it would show veins that no longer generate (and no biomes for them)
 * and none of the deposits. Its pages for replaced features are removed and each ore gets a deposit page in
 * the same "Ore generation" category instead, built from the summary the server syncs.
 */
final class EmiOresDeposits {
    private static final ResourceLocation OREGEN = ResourceLocation.fromNamespaceAndPath("emi_ores", "oregen");
    private static final String ORE_RECIPE_PREFIX = "/ore/";
    private static final int WIDTH = 160;
    private static final int TEXT_COLOR = 0x404040;

    private EmiOresDeposits() {
    }

    static void register(EmiRegistry registry) {
        Set<ResourceLocation> replaced = new HashSet<>(OreGenerationSummary.clientReplacedFeatures());
        if (!replaced.isEmpty()) {
            registry.removeRecipes(recipe -> isOreGen(recipe) && replaced.contains(featureId(recipe.getId())));
        }

        Registry<Biome> biomes = biomeRegistry();
        if (biomes == null) {
            return;
        }
        boolean vanillaLike = OreDepositOrePalette.clientVanillaLikeGeneration();
        int pages = 0;
        for (OreGenerationSummary.Entry entry : OreGenerationSummary.clientEntries()) {
            if (!BuiltInRegistries.BLOCK.containsKey(entry.oreId())) {
                continue;
            }
            EmiStack ore = EmiStack.of(BuiltInRegistries.BLOCK.get(entry.oreId()));
            if (ore.isEmpty()) {
                continue;
            }
            EmiIngredient biomeStacks = EmiIngredient.of(entry.biomes().stream()
                    .map(biomes::get).filter(Objects::nonNull).map(BiomeEmiStack::of).toList());
            registry.addRecipe(new DepositGenRecipe(entry, ore, biomeStacks, vanillaLike));
            pages++;
        }
        OresAndDrillsMod.LOGGER.info("EMI Ores: {} ore generation pages, {} replaced ore features hidden",
                pages, replaced.size());
    }

    private static Registry<Biome> biomeRegistry() {
        Minecraft minecraft = Minecraft.getInstance();
        RegistryAccess access = minecraft.level != null ? minecraft.level.registryAccess()
                : minecraft.getConnection() != null ? minecraft.getConnection().registryAccess() : null;
        return access == null ? null : access.registryOrThrow(Registries.BIOME);
    }

    private static boolean isOreGen(EmiRecipe recipe) {
        EmiRecipeCategory category = recipe.getCategory();
        return category != null && OREGEN.equals(category.getId());
    }

    /** EMI Ores 1.1+ wraps the feature id as {@code emi_ores:/ore/<namespace>/<path>}; 1.0 used it as is. */
    private static ResourceLocation featureId(ResourceLocation recipeId) {
        if (recipeId == null || !"emi_ores".equals(recipeId.getNamespace())) {
            return recipeId;
        }
        String path = recipeId.getPath();
        if (!path.startsWith(ORE_RECIPE_PREFIX)) {
            return null;
        }
        String feature = path.substring(ORE_RECIPE_PREFIX.length());
        int slash = feature.indexOf('/');
        return slash <= 0 ? null : ResourceLocation.tryBuild(feature.substring(0, slash), feature.substring(slash + 1));
    }

    private static final class DepositGenRecipe implements EmiRecipe {
        private final ResourceLocation id;
        private final EmiStack ore;
        private final EmiIngredient biomes;
        private final Component title;
        private final Component height;
        private final List<FormattedCharSequence> description;

        DepositGenRecipe(OreGenerationSummary.Entry entry, EmiStack ore, EmiIngredient biomes, boolean vanillaLike) {
            this.id = ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID,
                    "/ore_generation/" + entry.oreId().getNamespace() + "/" + entry.oreId().getPath());
            this.ore = ore;
            this.biomes = biomes;
            String kind = !entry.deposit() ? "ordinary_veins" : vanillaLike ? "vanilla_like" : "deposits";
            this.title = Component.translatable("recipe.ores_and_drills.ore_generation." + kind);
            this.height = Component.translatable("recipe.ores_and_drills.ore_generation.height", entry.minY(), entry.maxY());
            this.description = Minecraft.getInstance().font.split(
                    Component.translatable("recipe.ores_and_drills.ore_generation." + kind + ".description"), WIDTH);
        }

        @Override public EmiRecipeCategory getCategory() { return EmiOresRecipeCategories.OREGEN; }
        @Override public ResourceLocation getId() { return id; }
        @Override public List<EmiIngredient> getInputs() { return biomes.isEmpty() ? List.of() : List.of(biomes); }
        @Override public List<EmiStack> getOutputs() { return List.of(ore); }
        @Override public int getDisplayWidth() { return WIDTH; }
        @Override public int getDisplayHeight() { return 22 + description.size() * 10; }
        @Override public boolean supportsRecipeTree() { return false; }

        @Override
        public void addWidgets(WidgetHolder widgets) {
            widgets.addSlot(ore, 0, 0).recipeContext(this);
            widgets.addText(title, 22, 1, TEXT_COLOR, false);
            widgets.addText(height, 22, 11, TEXT_COLOR, false);
            if (!biomes.isEmpty()) {
                widgets.addSlot(biomes, WIDTH - 18, 0);
            }
            for (int line = 0; line < description.size(); line++) {
                widgets.addText(description.get(line), 0, 22 + line * 10, TEXT_COLOR, false);
            }
        }
    }
}
