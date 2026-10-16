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
import dev.world.level.levelgen.DepositLocator;
import dev.world.level.levelgen.DepositLocateStatus;
import dev.world.level.levelgen.DepositTerrainValidator;
import dev.world.level.levelgen.LocatedDeposit;
import dev.world.level.levelgen.OreDepositTier;
import dev.world.level.levelgen.OreSpawnDimensions;
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

import java.util.concurrent.CompletableFuture;
import java.util.Optional;

/** Registers {@code /locate oredeposit <ore|#ore_tag> [tier]}. */
public final class OreDepositLocateCommand {
    private static final DepositLocator LOCATOR = new DepositLocator(new DepositTerrainValidator());
    private static final SimpleCommandExceptionType ERROR_CATALOG_NOT_LOADED =
            new SimpleCommandExceptionType(Component.translatable(
                    "commands.ores_and_drills.locate.catalog_not_loaded"
            ));
    private static final DynamicCommandExceptionType ERROR_UNKNOWN_ORE =
            new DynamicCommandExceptionType(ore -> Component.translatable(
                    "commands.ores_and_drills.locate.unknown_ore", ore
            ));
    private static final DynamicCommandExceptionType ERROR_UNKNOWN_TIER =
            new DynamicCommandExceptionType(tier -> Component.translatable(
                    "commands.ores_and_drills.locate.unknown_tier", tier
            ));
    private static final DynamicCommandExceptionType ERROR_GENERATOR =
            new DynamicCommandExceptionType(selection -> Component.translatable(
                    "commands.ores_and_drills.locate.generator_error", selection
            ));

    private OreDepositLocateCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("locate")
                        .then(Commands.literal("oredeposit")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("ore", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.BLOCK))
                                        .suggests(OreDepositLocateCommand::suggestOres)
                                        .executes(context -> locate(context, Optional.empty()))
                                        .then(Commands.argument("tier", StringArgumentType.word())
                                                .suggests(OreDepositLocateCommand::suggestTiers)
                                                .executes(context -> locate(context, Optional.of(parseTier(context)))))))
        );
    }

    private static CompletableFuture<Suggestions> suggestOres(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder
    ) {
        return SharedSuggestionProvider.suggest(
                DepositCandidateResolver.oreSuggestionCatalog(context.getSource().getLevel()),
                builder
        );
    }

    private static CompletableFuture<Suggestions> suggestTiers(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder
    ) {
        return SharedSuggestionProvider.suggest(OreDepositTier.names(), builder);
    }

    private static OreDepositTier parseTier(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String tierName = StringArgumentType.getString(context, "tier");
        return OreDepositTier.fromName(tierName)
                .orElseThrow(() -> ERROR_UNKNOWN_TIER.create(tierName));
    }

    private static int locate(CommandContext<CommandSourceStack> context, Optional<OreDepositTier> requestedTier)
            throws CommandSyntaxException {
        if (!DepositCandidateResolver.isOreCatalogLoaded()) {
            throw ERROR_CATALOG_NOT_LOADED.create();
        }

        ResourceOrTagKeyArgument.Result<Block> selection = ResourceOrTagKeyArgument.getResourceOrTagKey(
                context,
                "ore",
                Registries.BLOCK,
                ERROR_UNKNOWN_ORE
        );
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String requestedSelection = selection.asPrintable();
        ResourceLocation requestedOre = resolveOreSelection(level, selection)
                .orElseThrow(() -> ERROR_UNKNOWN_ORE.create(requestedSelection));

        OreSpawnDimensions.ensureOriginalPlacedFeaturesScanned(level.registryAccess());
        BlockPos origin = BlockPos.containing(source.getPosition());
        LocatedDeposit result = null;
        try {
            Iterable<OreDepositTier> tiers = requestedTier
                    .<Iterable<OreDepositTier>>map(java.util.List::of)
                    .orElse(java.util.List.of(OreDepositTier.values()));
            for (OreDepositTier tier : tiers) {
                LocatedDeposit candidate = LOCATOR.findNearest(
                        level, origin, requestedOre, tier, DepositLocator.SEARCH_RADIUS
                ).orElse(null);
                if (candidate != null && isNearer(origin, result, candidate)) result = candidate;
            }
        } catch (RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug(
                    "Ore-deposit locate failed for {} tier {} in {}",
                    requestedSelection + " -> " + requestedOre,
                    requestedTier.map(OreDepositTier::serializedName).orElse("any"),
                    level.dimension().location(),
                    exception
            );
            throw ERROR_GENERATOR.create(requestedSelection + requestedTier
                    .map(tier -> " " + tier.serializedName()).orElse(""));
        }

        if (result == null) {
            source.sendFailure(requestedTier.<Component>map(tier -> Component.translatable(
                            "commands.ores_and_drills.locate.not_found",
                            Component.literal(requestedSelection), tier.serializedName(), DepositLocator.SEARCH_RADIUS
                    )).orElseGet(() -> Component.translatable(
                            "commands.ores_and_drills.locate.not_found_any_ore",
                            Component.literal(requestedSelection), DepositLocator.SEARCH_RADIUS
                    )));
            return 0;
        }

        OreDepositTier foundTier = result.candidate().tier();
        int foundDistance = result.distance();
        Component coordinates = clickableCoordinates(result.candidate().center());
        Component status = Component.translatable(switch (result.status()) {
            case CONFIRMED -> "commands.ores_and_drills.locate.status.confirmed";
            case SEED_PREDICTED -> "commands.ores_and_drills.locate.status.predicted";
        });
        Component placement = Component.translatable(switch (result.status()) {
            case CONFIRMED -> result.candidate().wallDirection() != null
                    ? "commands.ores_and_drills.locate.placement.wall"
                    : "commands.ores_and_drills.locate.placement.underground";
            case SEED_PREDICTED -> "commands.ores_and_drills.locate.placement.underground";
        });
        source.sendSuccess(() -> Component.translatable(
                "commands.ores_and_drills.locate.success",
                Component.literal(requestedSelection),
                foundTier.serializedName(),
                coordinates,
                foundDistance
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "commands.ores_and_drills.locate.placement", placement
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "commands.ores_and_drills.locate.status", status
        ), false);
        if (result.status() == DepositLocateStatus.SEED_PREDICTED) {
            int coveragePercent = (int) Math.round(result.stoneCoverage() * 100.0D);
            source.sendSuccess(() -> Component.translatable(
                    "commands.ores_and_drills.locate.rock_coverage", coveragePercent
            ), false);
        }
        return 1;
    }

    private static boolean isNearer(BlockPos origin, LocatedDeposit current, LocatedDeposit candidate) {
        if (current == null) return true;
        long candidateDistance = DepositLocator.horizontalDistanceSquared(origin, candidate.candidate().center());
        long currentDistance = DepositLocator.horizontalDistanceSquared(origin, current.candidate().center());
        return candidateDistance < currentDistance
                || candidateDistance == currentDistance && candidate.confirmed() && !current.confirmed();
    }

    private static Optional<ResourceLocation> resolveOreSelection(
            ServerLevel level,
            ResourceOrTagKeyArgument.Result<Block> selection
    ) {
        return selection.unwrap().map(
                blockKey -> DepositCandidateResolver.isKnownOre(level, blockKey.location())
                        ? Optional.of(blockKey.location())
                        : Optional.empty(),
                tagKey -> DepositCandidateResolver.oreForTag(tagKey.location())
        );
    }

    private static Component clickableCoordinates(BlockPos pos) {
        return ComponentUtils.wrapInSquareBrackets(
                        Component.translatable("chat.coordinates", pos.getX(), pos.getY(), pos.getZ()))
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.SUGGEST_COMMAND,
                                "/tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        ))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("chat.coordinates.tooltip")
                        )));
    }
}
