package sereneseasons.season;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import sereneseasons.api.season.ISeasonColorProvider;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.init.ModConfig;
import sereneseasons.init.ModTags;
import sereneseasons.util.SeasonColorUtil;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

public class SeasonColorHandlers
{
    private static final Multimap<ResolverType, ColorOverride> resolverOverrides = HashMultimap.create();

    public static void setup()
    {
        registerGrassAndFoliageColorHandlers();
    }

    public static void registerResolverOverride(ResolverType type, ColorOverride override)
    {
        resolverOverrides.put(type, override);
    }

    private static ColorResolver originalGrassColorResolver;
    private static ColorResolver originalFoliageColorResolver;

    private static void registerGrassAndFoliageColorHandlers()
    {
        originalGrassColorResolver = BiomeColors.GRASS_COLOR_RESOLVER;
        originalFoliageColorResolver = BiomeColors.FOLIAGE_COLOR_RESOLVER;

        BiomeColors.GRASS_COLOR_RESOLVER = (biome, x, z) -> resolveColors(ResolverType.GRASS, biome, x, z);
        BiomeColors.FOLIAGE_COLOR_RESOLVER = (biome, x, z) -> resolveColors(ResolverType.FOLIAGE, biome, x, z);
    }

    private static int resolveColors(ResolverType type, Biome biome, double x, double z)
    {
        // This is called by BiomeColors.RESOLVER which DH uses for LOD chunks
        // We need to apply seasonal colors the SAME WAY as the block color providers do

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;

        if (level == null)
        {
            // No level context, return vanilla color
            return switch (type) {
                case GRASS -> originalGrassColorResolver.getColor(biome, x, z);
                case FOLIAGE -> originalFoliageColorResolver.getColor(biome, x, z);
            };
        }

        // We need to get a Holder<Biome> from the Biome object to check tags
        // The biome parameter DH passes us is the correct one, we just need to wrap it in a holder
        Registry<Biome> biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);

        // Try to get the resource key for this biome, then look up the holder
        Optional<ResourceKey<Biome>> biomeKey = biomeRegistry.getResourceKey(biome);
        if (biomeKey.isEmpty())
        {
            // Can't find the biome key, fall back to vanilla color
            return switch (type) {
                case GRASS -> originalGrassColorResolver.getColor(biome, x, z);
                case FOLIAGE -> originalFoliageColorResolver.getColor(biome, x, z);
            };
        }

        // Now get the holder from the key
        Optional<Holder.Reference<Biome>> holderOpt = biomeRegistry.get(biomeKey.get());
        if (holderOpt.isEmpty())
        {
            // Can't find the holder, fall back to vanilla color
            return switch (type) {
                case GRASS -> originalGrassColorResolver.getColor(biome, x, z);
                case FOLIAGE -> originalFoliageColorResolver.getColor(biome, x, z);
            };
        }

        Holder<Biome> biomeHolder = holderOpt.get();

        // Get the original vanilla color using the biome parameter DH passed us
        int originalColor = switch (type) {
            case GRASS -> originalGrassColorResolver.getColor(biome, x, z);
            case FOLIAGE -> originalFoliageColorResolver.getColor(biome, x, z);
        };

        // Special handling: Dark forest biomes should use evergreen color as base for foliage
        // This matches how vanilla dark oak leaves work
        if (type == ResolverType.FOLIAGE && biomeKey.get().location().getPath().contains("dark_forest"))
        {
            originalColor = FoliageColor.FOLIAGE_EVERGREEN;
        }

        // Now call getSeasonalColor() with the proper holder AND the original color
        // This matches how it was done in 1.21.8
        return getSeasonalColor(level, biomeHolder, x, z, type, originalColor);
    }

    public static int getSeasonalColor(@Nullable Level level, Holder<Biome> biomeHolder, double x, double z, ResolverType type)
    {
        int originalColor = originalColorFor(biomeHolder, x, z, type);
        return getSeasonalColor(level, biomeHolder, x, z, type, originalColor);
    }

    public static int getSeasonalColor(@Nullable Level level, Holder<Biome> biomeHolder, double x, double z, ResolverType type, int originalColor)
    {
        if (level == null || biomeHolder == null)
        {
            return originalColor;
        }

        if (biomeHolder.is(ModTags.Biomes.BLACKLISTED_BIOMES) || !ModConfig.seasons.isDimensionWhitelisted(level.dimension()))
        {
            return originalColor;
        }

        ISeasonState calendar = SeasonHelper.getSeasonState(level);
        ISeasonColorProvider colorProvider = biomeHolder.is(ModTags.Biomes.TROPICAL_BIOMES) ? calendar.getTropicalSeason() : calendar.getSubSeason();

        int seasonalColor = switch (type) {
            case GRASS -> SeasonColorUtil.applySeasonalGrassColouring(colorProvider, biomeHolder, originalColor);
            case FOLIAGE -> SeasonColorUtil.applySeasonalFoliageColouring(colorProvider, biomeHolder, originalColor);
        };

        int currentColor = seasonalColor;
        for (ColorOverride override : resolverOverrides.get(type))
        {
            currentColor = override.apply(originalColor, seasonalColor, currentColor, biomeHolder, x, z);
        }

        return currentColor;
    }

    private static int originalColorFor(Holder<Biome> biomeHolder, double x, double z, ResolverType type)
    {
        ColorResolver resolver = switch (type) {
            case GRASS -> originalGrassColorResolver;
            case FOLIAGE -> originalFoliageColorResolver;
        };

        if (resolver != null)
        {
            return resolver.getColor(biomeHolder.value(), x, z);
        }

        return switch (type) {
            case GRASS -> biomeHolder.value().getGrassColor(x, z);
            case FOLIAGE -> biomeHolder.value().getFoliageColor();
        };
    }

    public interface ColorOverride
    {
        int apply(int originalColor, int seasonalColor, int currentColor, Holder<Biome> biome, double x, double z);
    }

    public enum ResolverType
    {
        GRASS, FOLIAGE
    }
}
