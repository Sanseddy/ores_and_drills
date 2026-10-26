package dev.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.OresAndDrillsMod;
import dev.world.level.levelgen.DepositCandidateResolver;
import dev.world.level.levelgen.DepositLocateStatus;
import dev.world.level.levelgen.DepositLocator;
import dev.world.level.levelgen.LocatedDeposit;
import dev.world.level.levelgen.OreDepositTier;
import dev.world.level.levelgen.OreSpawnDimensions;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Registers {@code /locate oredeposit <ore|#ore_tag> [tier]}. */
public final class OreDepositLocateCommand {
    private static final SimpleCommandExceptionType ERROR_CATALOG_NOT_LOADED =
            new SimpleCommandExceptionType(
                    Component.translatable("commands.ores_and_drills.locate.catalog_not_loaded"));
    private static final DynamicCommandExceptionType ERROR_UNKNOWN_ORE =
            new DynamicCommandExceptionType(
                    ore ->
                            Component.translatable(
                                    "commands.ores_and_drills.locate.unknown_ore", ore));
    private static final DynamicCommandExceptionType ERROR_UNKNOWN_TIER =
            new DynamicCommandExceptionType(
                    tier ->
                            Component.translatable(
                                    "commands.ores_and_drills.locate.unknown_tier", tier));

    private OreDepositLocateCommand() {}

    public static void register(RegisterCommandsEvent event) {
        var tier =
                Commands.argument("tier", StringArgumentType.word())
                        .suggests(OreDepositLocateCommand::suggestTiers)
                        .executes(context -> locate(context, Optional.of(parseTier(context))));
        var ore =
                Commands.argument(
                                "ore", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.BLOCK))
                        .suggests(OreDepositLocateCommand::suggestOres)
                        .executes(context -> locate(context, Optional.empty()))
                        .then(tier);
        event.getDispatcher()
                .register(
                        Commands.literal("locate")
                                .then(
                                        Commands.literal("oredeposit")
                                                .requires(source -> source.hasPermission(2))
                                                .then(ore)));
    }

    /**
     * Unlike vanilla, which matches only from the start or after {@code _} and {@code .}, the input
     * also matches after {@code :} and {@code /}: {@code coal} suggests {@code #c:ores/coal} and
     * {@code minecraft:coal_ore}.
     */
    private static CompletableFuture<Suggestions> suggestOres(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String input = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String suggestion :
                DepositCandidateResolver.oreSuggestionCatalog(context.getSource().getLevel())) {
            if (matchesAnySegment(input, suggestion.toLowerCase(Locale.ROOT))) {
                builder.suggest(suggestion);
            }
        }
        return builder.buildFuture();
    }

    private static boolean matchesAnySegment(String input, String suggestion) {
        if (SharedSuggestionProvider.matchesSubStr(input, suggestion)) {
            return true;
        }
        for (int index = 0; index < suggestion.length(); index++) {
            char separator = suggestion.charAt(index);
            if ((separator == ':' || separator == '/')
                    && SharedSuggestionProvider.matchesSubStr(
                            input, suggestion.substring(index + 1))) {
                return true;
            }
        }
        return false;
    }

    private static CompletableFuture<Suggestions> suggestTiers(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(OreDepositTier.names(), builder);
    }

    private static OreDepositTier parseTier(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        String tierName = StringArgumentType.getString(context, "tier");
        return OreDepositTier.fromName(tierName)
                .orElseThrow(() -> ERROR_UNKNOWN_TIER.create(tierName));
    }

    private static int locate(
            CommandContext<CommandSourceStack> context, Optional<OreDepositTier> requestedTier)
            throws CommandSyntaxException {
        if (!DepositCandidateResolver.isOreCatalogLoaded()) {
            throw ERROR_CATALOG_NOT_LOADED.create();
        }

        ResourceOrTagKeyArgument.Result<Block> selection =
                ResourceOrTagKeyArgument.getResourceOrTagKey(
                        context, "ore", Registries.BLOCK, ERROR_UNKNOWN_ORE);
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String requestedSelection = selection.asPrintable();
        ResourceLocation requestedOre =
                resolveOreSelection(level, selection)
                        .orElseThrow(() -> ERROR_UNKNOWN_ORE.create(requestedSelection));

        OreSpawnDimensions.ensureOriginalPlacedFeaturesScanned(level.registryAccess());
        BlockPos origin = BlockPos.containing(source.getPosition());
        if (requestedTier.isPresent()
                && !DepositLocator.isLocatable(level, requestedOre, requestedTier.get())) {
            source.sendFailure(
                    Component.translatable(
                            "commands.ores_and_drills.locate.vanilla_like_small",
                            requestedTier.get().serializedName()));
            return 0;
        }
        java.util.List<OreDepositTier> tiers =
                requestedTier
                        .map(java.util.List::of)
                        .orElse(
                                java.util.Arrays.stream(OreDepositTier.values())
                                        .filter(
                                                tier ->
                                                        DepositLocator.isLocatable(
                                                                level, requestedOre, tier))
                                        .toList());
        OreDepositLocateService.SubmitResult submitted =
                OreDepositLocateService.submit(
                        level,
                        origin,
                        requestedOre,
                        tiers,
                        DepositLocator.SEARCH_RADIUS,
                        (result, failure, metrics) ->
                                finishLocate(
                                        source,
                                        requestedSelection,
                                        requestedTier,
                                        result.orElse(null),
                                        failure,
                                        metrics));
        if (submitted == OreDepositLocateService.SubmitResult.BUSY) {
            source.sendFailure(Component.translatable("commands.ores_and_drills.locate.busy"));
            return 0;
        }
        if (submitted != OreDepositLocateService.SubmitResult.CACHED) {
            source.sendSuccess(
                    () ->
                            Component.translatable(
                                    "commands.ores_and_drills.locate.searching",
                                    Component.literal(requestedSelection),
                                    requestedTier.map(OreDepositTier::serializedName).orElse("any"),
                                    DepositLocator.SEARCH_RADIUS),
                    false);
        }
        return 1;
    }

    private static void finishLocate(
            CommandSourceStack source,
            String requestedSelection,
            Optional<OreDepositTier> requestedTier,
            LocatedDeposit result,
            Throwable failure,
            OreDepositLocateService.Metrics metrics) {
        if (failure != null) {
            OresAndDrillsMod.LOGGER.debug(
                    "Ore-deposit locate failed for {} tier {} in {}",
                    requestedSelection,
                    requestedTier.map(OreDepositTier::serializedName).orElse("any"),
                    source.getLevel().dimension().location(),
                    failure);
            source.sendFailure(
                    Component.translatable(
                            "commands.ores_and_drills.locate.generator_error",
                            requestedSelection
                                    + requestedTier
                                            .map(tier -> " " + tier.serializedName())
                                            .orElse("")));
            return;
        }
        OresAndDrillsMod.LOGGER.debug(
                "Ore-deposit locate completed in {} ms after {} cells and {} candidates",
                metrics.elapsedMillis(),
                metrics.cellsChecked(),
                metrics.candidatesChecked());
        if (result == null) {
            source.sendFailure(
                    requestedTier
                            .<Component>map(
                                    tier ->
                                            Component.translatable(
                                                    "commands.ores_and_drills.locate.not_found",
                                                    Component.literal(requestedSelection),
                                                    tier.serializedName(),
                                                    DepositLocator.SEARCH_RADIUS))
                            .orElseGet(
                                    () ->
                                            Component.translatable(
                                                    "commands.ores_and_drills.locate.not_found_any_ore",
                                                    Component.literal(requestedSelection),
                                                    DepositLocator.SEARCH_RADIUS)));
            return;
        }

        OreDepositTier foundTier = result.candidate().tier();
        int foundDistance = result.distance();
        Component coordinates = clickableCoordinates(result.candidate().center());
        Component status =
                Component.translatable(
                        switch (result.status()) {
                            case CONFIRMED -> "commands.ores_and_drills.locate.status.confirmed";
                            case SEED_PREDICTED ->
                                    "commands.ores_and_drills.locate.status.predicted";
                        });
        Component placement =
                Component.translatable(
                        switch (result.status()) {
                            case CONFIRMED ->
                                    result.candidate().wallDirection() != null
                                            ? "commands.ores_and_drills.locate.placement.wall"
                                            : "commands.ores_and_drills.locate.placement.underground";
                            case SEED_PREDICTED ->
                                    "commands.ores_and_drills.locate.placement.underground";
                        });
        source.sendSuccess(
                () ->
                        Component.translatable(
                                "commands.ores_and_drills.locate.success",
                                Component.literal(requestedSelection),
                                foundTier.serializedName(),
                                coordinates,
                                foundDistance),
                false);
        source.sendSuccess(
                () ->
                        Component.translatable(
                                "commands.ores_and_drills.locate.placement", placement),
                false);
        source.sendSuccess(
                () -> Component.translatable("commands.ores_and_drills.locate.status", status),
                false);
        if (result.status() == DepositLocateStatus.SEED_PREDICTED) {
            int coveragePercent = (int) Math.round(result.stoneCoverage() * 100.0D);
            source.sendSuccess(
                    () ->
                            Component.translatable(
                                    "commands.ores_and_drills.locate.rock_coverage",
                                    coveragePercent),
                    false);
        }
    }

    private static Optional<ResourceLocation> resolveOreSelection(
            ServerLevel level, ResourceOrTagKeyArgument.Result<Block> selection) {
        return selection
                .unwrap()
                .map(
                        blockKey ->
                                DepositCandidateResolver.isKnownOre(level, blockKey.location())
                                        ? Optional.of(blockKey.location())
                                        : Optional.empty(),
                        tagKey -> DepositCandidateResolver.oreForTag(tagKey.location()));
    }

    private static Component clickableCoordinates(BlockPos pos) {
        return ComponentUtils.wrapInSquareBrackets(
                        Component.translatable(
                                "chat.coordinates", pos.getX(), pos.getY(), pos.getZ()))
                .withStyle(
                        style ->
                                style.withColor(ChatFormatting.GREEN)
                                        .withClickEvent(
                                                new ClickEvent(
                                                        ClickEvent.Action.SUGGEST_COMMAND,
                                                        "/tp @s "
                                                                + pos.getX()
                                                                + " "
                                                                + pos.getY()
                                                                + " "
                                                                + pos.getZ()))
                                        .withHoverEvent(
                                                new HoverEvent(
                                                        HoverEvent.Action.SHOW_TEXT,
                                                        Component.translatable(
                                                                "chat.coordinates.tooltip"))));
    }
}
