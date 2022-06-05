package org.betterx.bclib.api.biomes;

import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.data.BuiltinRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.GenerationStep.Decoration;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.impl.biome.NetherBiomeData;
import net.fabricmc.fabric.impl.biome.TheEndBiomeData;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.mutable.MutableInt;
import org.betterx.bclib.BCLib;
import org.betterx.bclib.api.features.BCLFeature;
import org.betterx.bclib.api.tag.CommonBiomeTags;
import org.betterx.bclib.api.tag.TagAPI;
import org.betterx.bclib.interfaces.BiomeSourceAccessor;
import org.betterx.bclib.interfaces.NoiseGeneratorSettingsProvider;
import org.betterx.bclib.interfaces.SurfaceMaterialProvider;
import org.betterx.bclib.interfaces.SurfaceRuleProvider;
import org.betterx.bclib.mixin.common.BiomeGenerationSettingsAccessor;
import org.betterx.bclib.mixin.common.MobSpawnSettingsAccessor;
import org.betterx.bclib.util.CollectionsUtil;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

public class BiomeAPI {
    public static class BiomeType {
        public static final BiomeType NONE = new BiomeType("NONE");
        public static final BiomeType OVERWORLD = new BiomeType("OVERWORLD");
        public static final BiomeType NETHER = new BiomeType("NETHER");
        public static final BiomeType BCL_NETHER = new BiomeType("BCL_NETHER", NETHER);
        public static final BiomeType OTHER_NETHER = new BiomeType("OTHER_NETHER", NETHER);
        public static final BiomeType END = new BiomeType("END");
        public static final BiomeType END_LAND = new BiomeType("END_LAND", END);
        public static final BiomeType END_VOID = new BiomeType("END_VOID", END);
        public static final BiomeType BCL_END_LAND = new BiomeType("BCL_END_LAND", END_LAND);
        public static final BiomeType BCL_END_VOID = new BiomeType("BCL_END_VOID", END_VOID);
        public static final BiomeType OTHER_END_LAND = new BiomeType("OTHER_END_LAND", END_LAND);
        public static final BiomeType OTHER_END_VOID = new BiomeType("OTHER_END_VOID", END_VOID);

        private static final Map<ResourceLocation, BiomeType> BIOME_TYPE_MAP = Maps.newHashMap();
        public final BiomeType parentOrNull;
        private final String debugName;

        public BiomeType(String debugName) {
            this(debugName, null);
        }

        public BiomeType(String debugName, BiomeType parentOrNull) {
            this.parentOrNull = parentOrNull;
            this.debugName = debugName;
        }

        public boolean is(BiomeType d) {
            if (d == this) return true;
            if (parentOrNull != null) return parentOrNull.is(d);
            return false;
        }

        @Override
        public String toString() {
            String str = debugName;
            if (parentOrNull != null) str += " -> " + parentOrNull.toString();
            return str;
        }
    }

    /**
     * Empty biome used as default value if requested biome doesn't exist or linked. Shouldn't be registered anywhere to prevent bugs.
     * Have {@code Biomes.THE_VOID} as the reference biome.
     */
    public static final BCLBiome EMPTY_BIOME = new BCLBiome(Biomes.THE_VOID.location());

    private static final Map<ResourceLocation, BCLBiome> ID_MAP = Maps.newHashMap();
    private static final Map<Biome, BCLBiome> CLIENT = Maps.newHashMap();
    private static Registry<Biome> biomeRegistry;
    private static RegistryAccess registryAccess;

    private static final Map<Holder<PlacedFeature>, Integer> FEATURE_ORDER = Maps.newHashMap();
    private static final MutableInt FEATURE_ORDER_ID = new MutableInt(0);

    private static final Map<ResourceKey<LevelStem>, List<BiConsumer<ResourceLocation, Holder<Biome>>>> MODIFICATIONS = Maps.newHashMap();
    private static final Map<ResourceKey, List<BiConsumer<ResourceLocation, Holder<Biome>>>> TAG_ADDERS = Maps.newHashMap();

    public static final BCLBiome NETHER_WASTES_BIOME = registerNetherBiome(getFromRegistry(Biomes.NETHER_WASTES).value());
    public static final BCLBiome CRIMSON_FOREST_BIOME = registerNetherBiome(getFromRegistry(Biomes.CRIMSON_FOREST).value());
    public static final BCLBiome WARPED_FOREST_BIOME = registerNetherBiome(getFromRegistry(Biomes.WARPED_FOREST).value());
    public static final BCLBiome SOUL_SAND_VALLEY_BIOME = registerNetherBiome(getFromRegistry(Biomes.SOUL_SAND_VALLEY).value());
    public static final BCLBiome BASALT_DELTAS_BIOME = registerNetherBiome(getFromRegistry(Biomes.BASALT_DELTAS).value());

    public static final BCLBiome THE_END = registerEndLandBiome(getFromRegistry(Biomes.THE_END));
    public static final BCLBiome END_MIDLANDS = registerSubBiome(THE_END,
            getFromRegistry(Biomes.END_MIDLANDS).value(),
            0.5F);
    public static final BCLBiome END_HIGHLANDS = registerSubBiome(THE_END,
            getFromRegistry(Biomes.END_HIGHLANDS).value(),
            0.5F);

    public static final BCLBiome END_BARRENS = registerEndVoidBiome(getFromRegistry(new ResourceLocation("end_barrens")));
    public static final BCLBiome SMALL_END_ISLANDS = registerEndVoidBiome(getFromRegistry(new ResourceLocation(
            "small_end_islands")));

    private static void initFeatureOrder() {
        if (!FEATURE_ORDER.isEmpty()) {
            return;
        }

        BuiltinRegistries.BIOME
                .entrySet()
                .stream()
                .filter(entry -> entry
                        .getKey()
                        .location()
                        .getNamespace()
                        .equals("minecraft"))
                .map(Entry::getValue)
                .map(biome -> (BiomeGenerationSettingsAccessor) biome.getGenerationSettings())
                .map(BiomeGenerationSettingsAccessor::bclib_getFeatures)
                .forEach(stepFeatureSuppliers -> stepFeatureSuppliers.forEach(step -> step.forEach(feature -> {
                    FEATURE_ORDER.computeIfAbsent(feature, f -> FEATURE_ORDER_ID.getAndIncrement());
                })));
    }

    /**
     * Initialize registry for current server.
     *
     * @param access - The new, active {@link RegistryAccess} for the current session.
     */
    public static void initRegistry(RegistryAccess access) {
        if (access != registryAccess) {
            BiomeAPI.registryAccess = access;
            Registry<Biome> biomeRegistry = access.registry(Registry.BIOME_REGISTRY).orElse(null);

            if (biomeRegistry != BiomeAPI.biomeRegistry) {
                BiomeAPI.biomeRegistry = biomeRegistry;
                CLIENT.clear();
            }
        }
    }

    /**
     * For internal use only.
     * <p>
     * This method gets called before a world is loaded/created to flush cashes we build.
     */
    public static void prepareNewLevel() {

    }

    /**
     * Register {@link BCLBiome} instance and its {@link Biome} if necessary.
     *
     * @param bclbiome {@link BCLBiome}
     * @param dim      The Dimension fo rthis Biome
     * @return {@link BCLBiome}
     */
    public static BCLBiome registerBiome(BCLBiome bclbiome, BiomeType dim) {
        if (BuiltinRegistries.BIOME.get(bclbiome.getID()) == null) {
            final Biome biome = bclbiome.getBiome();
            ResourceLocation loc = bclbiome.getID();
            Registry.register(BuiltinRegistries.BIOME, loc, biome);
        }
        ID_MAP.put(bclbiome.getID(), bclbiome);
        BiomeType.BIOME_TYPE_MAP.put(bclbiome.getID(), dim);

        if (dim != null && dim.is(BiomeType.NETHER)) {
            TagAPI.addBiomeTag(BiomeTags.IS_NETHER, bclbiome.getBiome());
            TagAPI.addBiomeTag(CommonBiomeTags.IN_NETHER, bclbiome.getBiome());
        } else if (dim != null && dim.is(BiomeType.END)) {
            TagAPI.addBiomeTag(BiomeTags.IS_END, bclbiome.getBiome());
        }

        bclbiome.afterRegistration();

        return bclbiome;
    }

    public static BCLBiome registerSubBiome(BCLBiome parent, BCLBiome subBiome) {
        return registerSubBiome(parent,
                subBiome,
                BiomeType.BIOME_TYPE_MAP.getOrDefault(parent.getID(), BiomeType.NONE));
    }

    public static BCLBiome registerSubBiome(BCLBiome parent, Biome subBiome, float genChance) {
        return registerSubBiome(parent,
                subBiome,
                genChance,
                BiomeType.BIOME_TYPE_MAP.getOrDefault(parent.getID(), BiomeType.NONE));
    }

    public static BCLBiome registerSubBiome(BCLBiome parent, BCLBiome subBiome, BiomeType dim) {
        registerBiome(subBiome, dim);
        parent.addSubBiome(subBiome);

        return subBiome;
    }

    public static BCLBiome registerSubBiome(BCLBiome parent, Biome biome, float genChance, BiomeType dim) {
        BCLBiome subBiome = new BCLBiome(biome, VanillaBiomeSettings.createVanilla().setGenChance(genChance).build());
        return registerSubBiome(parent, subBiome, dim);
    }

    /**
     * Register {@link BCLBiome} instance and its {@link Biome} if necessary.
     * After that biome will be added to BCLib Nether Biome Generator and into Fabric Biome API.
     *
     * @param bclBiome {@link BCLBiome}
     * @return {@link BCLBiome}
     */
    public static BCLBiome registerNetherBiome(BCLBiome bclBiome) {
        registerBiome(bclBiome, BiomeType.BCL_NETHER);

        ResourceKey<Biome> key = BiomeAPI.getBiomeKey(bclBiome.getBiome());
        if (bclBiome.allowFabricRegistration()) {
            bclBiome.forEachClimateParameter(p -> NetherBiomeData.addNetherBiome(key, p));
        }
        return bclBiome;
    }

    /**
     * Register {@link BCLBiome} instance and its {@link Biome} if necessary.
     * After that biome will be added to BCLib Nether Biome Generator and into Fabric Biome API.
     *
     * @param biome {@link BCLBiome}
     * @return {@link BCLBiome}
     */
    public static BCLBiome registerNetherBiome(Biome biome) {
        BCLBiome bclBiome = new BCLBiome(biome, null);
        registerBiome(bclBiome, BiomeType.OTHER_NETHER);
        return bclBiome;
    }

    /**
     * Register {@link BCLBiome} instance and its {@link Biome} if necessary.
     * After that biome will be added to BCLib End Biome Generator and into Fabric Biome API as a land biome (will generate only on islands).
     *
     * @param biome {@link BCLBiome}
     * @return {@link BCLBiome}
     */
    public static BCLBiome registerEndLandBiome(BCLBiome biome) {
        registerBiome(biome, BiomeType.BCL_END_LAND);

        float weight = biome.getGenChance();
        ResourceKey<Biome> key = BiomeAPI.getBiomeKey(biome.getBiome());
        if (biome.allowFabricRegistration()) {
            TheEndBiomeData.addEndBiomeReplacement(Biomes.END_HIGHLANDS, key, weight);
            TheEndBiomeData.addEndBiomeReplacement(Biomes.END_MIDLANDS, key, weight);
        }
        return biome;
    }

    /**
     * Register {@link BCLBiome} wrapper for {@link Biome}.
     * After that biome will be added to BCLib End Biome Generator and into Fabric Biome API as a land biome (will generate only on islands).
     *
     * @param biome {@link BCLBiome}
     * @return {@link BCLBiome}
     */
    public static BCLBiome registerEndLandBiome(Holder<Biome> biome) {
        BCLBiome bclBiome = new BCLBiome(biome.value(), null);

        registerBiome(bclBiome, BiomeType.OTHER_END_LAND);
        return bclBiome;
    }

    /**
     * Register {@link BCLBiome} wrapper for {@link Biome}.
     * After that biome will be added to BCLib End Biome Generator and into Fabric Biome API as a land biome (will generate only on islands).
     *
     * @param biome     {@link BCLBiome};
     * @param genChance float generation chance.
     * @return {@link BCLBiome}
     */
    public static BCLBiome registerEndLandBiome(Holder<Biome> biome, float genChance) {
        BCLBiome bclBiome = new BCLBiome(biome.value(),
                VanillaBiomeSettings.createVanilla().setGenChance(genChance).build());

        registerBiome(bclBiome, BiomeType.OTHER_END_LAND);
        return bclBiome;
    }

    /**
     * Register {@link BCLBiome} instance and its {@link Biome} if necessary.
     * After that biome will be added to BCLib End Biome Generator and into Fabric Biome API as a void biome (will generate only in the End void - between islands).
     *
     * @param biome {@link BCLBiome}
     * @return {@link BCLBiome}
     */
    public static BCLBiome registerEndVoidBiome(BCLBiome biome) {
        registerBiome(biome, BiomeType.END_VOID);

        float weight = biome.getGenChance();
        ResourceKey<Biome> key = BiomeAPI.getBiomeKey(biome.getBiome());
        if (biome.allowFabricRegistration()) {
            TheEndBiomeData.addEndBiomeReplacement(Biomes.SMALL_END_ISLANDS, key, weight);
        }
        return biome;
    }

    /**
     * Register {@link BCLBiome} instance and its {@link Biome} if necessary.
     * After that biome will be added to BCLib End Biome Generator and into Fabric Biome API as a void biome (will generate only in the End void - between islands).
     *
     * @param biome {@link BCLBiome}
     * @return {@link BCLBiome}
     */
    public static BCLBiome registerEndVoidBiome(Holder<Biome> biome) {
        BCLBiome bclBiome = new BCLBiome(biome.value(), null);

        registerBiome(bclBiome, BiomeType.END_VOID);
        return bclBiome;
    }

    /**
     * Register {@link BCLBiome} instance and its {@link Biome} if necessary.
     * After that biome will be added to BCLib End Biome Generator and into Fabric Biome API as a void biome (will generate only in the End void - between islands).
     *
     * @param biome     {@link BCLBiome}.
     * @param genChance float generation chance.
     * @return {@link BCLBiome}
     */
    public static BCLBiome registerEndVoidBiome(Holder<Biome> biome, float genChance) {
        BCLBiome bclBiome = new BCLBiome(biome.value(),
                VanillaBiomeSettings.createVanilla().setGenChance(genChance).build());

        registerBiome(bclBiome, BiomeType.END_VOID);
        return bclBiome;
    }

    /**
     * Get {@link BCLBiome} from {@link Biome} instance on server. Used to convert world biomes to BCLBiomes.
     *
     * @param biome - {@link Holder<Biome>} from world.
     * @return {@link BCLBiome} or {@code BiomeAPI.EMPTY_BIOME}.
     */
    public static BCLBiome getFromBiome(Holder<Biome> biome) {
        if (biomeRegistry == null) {
            return EMPTY_BIOME;
        }
        return ID_MAP.getOrDefault(biome.unwrapKey().orElseThrow().location(), EMPTY_BIOME);
    }

    /**
     * Get {@link BCLBiome} from biome on client. Used in fog rendering.
     *
     * @param biome - {@link Biome} from client world.
     * @return {@link BCLBiome} or {@code BiomeAPI.EMPTY_BIOME}.
     */
    @Environment(EnvType.CLIENT)
    public static BCLBiome getRenderBiome(Biome biome) {
        BCLBiome endBiome = CLIENT.get(biome);
        if (endBiome == null) {
            Minecraft minecraft = Minecraft.getInstance();
            ResourceLocation id = minecraft.level.registryAccess()
                                                 .registryOrThrow(Registry.BIOME_REGISTRY)
                                                 .getKey(biome);
            endBiome = id == null ? EMPTY_BIOME : ID_MAP.getOrDefault(id, EMPTY_BIOME);
            CLIENT.put(biome, endBiome);
        }
        return endBiome;
    }

    /**
     * Get biome {@link ResourceKey} from given {@link Biome}.
     *
     * @param biome - {@link Biome} from server world.
     * @return biome {@link ResourceKey} or {@code null}.
     */
    @Nullable
    public static ResourceKey getBiomeKey(Biome biome) {
        if (biomeRegistry != null) {
            Optional<ResourceKey<Biome>> key = biomeRegistry.getResourceKey(biome);
            if (key.isPresent()) return key.get();
        }
        return BuiltinRegistries.BIOME
                .getResourceKey(biome)
                .orElseGet(null);
    }

    /**
     * Get biome {@link ResourceLocation} from given {@link Biome}.
     *
     * @param biome - {@link Biome} from server world.
     * @return biome {@link ResourceLocation}.
     */
    public static ResourceLocation getBiomeID(Biome biome) {
        ResourceLocation id = null;
        if (biomeRegistry != null) {
            id = biomeRegistry.getKey(biome);
        }
        if (id == null) {
            id = BuiltinRegistries.BIOME.getKey(biome);
        }

        if (id == null) {
            BCLib.LOGGER.error("Unable to get ID for " + biome + ". Falling back to empty Biome...");
            id = EMPTY_BIOME.getID();
        }

        return id;
    }

    /**
     * Get biome {@link ResourceLocation} from given {@link Biome}.
     *
     * @param biome - {@link Holder<Biome>} from server world.
     * @return biome {@link ResourceLocation}.
     */
    public static ResourceLocation getBiomeID(Holder<Biome> biome) {
        var oKey = biome.unwrapKey();
        if (oKey.isPresent()) {
            return oKey.get().location();
        }
        return null;
    }

    public static ResourceKey getBiomeKey(Holder<Biome> biome) {
        return biome.unwrapKey().orElse(null);
    }

    public static ResourceKey getBiomeKeyOrThrow(Holder<Biome> biome) {
        return biome.unwrapKey().orElseThrow();
    }

    public static Holder<Biome> getBiomeHolder(BCLBiome biome) {
        return getBiomeHolder(biome.getBiome());
    }

    public static Holder<Biome> getBiomeHolder(Biome biome) {
        if (biomeRegistry != null) {
            Optional<ResourceKey<Biome>> key = biomeRegistry.getResourceKey(biome);
            if (key.isPresent()) return biomeRegistry.getOrCreateHolderOrThrow(key.get());
        }

        return BuiltinRegistries.BIOME.getOrCreateHolderOrThrow(BiomeAPI.getBiomeKey(biome));
    }

    public static Holder<Biome> getBiomeHolder(ResourceLocation biome) {
        if (biomeRegistry != null) {
            return getBiomeHolder(biomeRegistry.get(biome));
        }
        return getBiomeHolder(BuiltinRegistries.BIOME.get(biome));
    }

    /**
     * Get {@link BCLBiome} from given {@link ResourceLocation}.
     *
     * @param biomeID - biome {@link ResourceLocation}.
     * @return {@link BCLBiome} or {@code BiomeAPI.EMPTY_BIOME}.
     */
    public static BCLBiome getBiome(ResourceLocation biomeID) {
        return ID_MAP.getOrDefault(biomeID, EMPTY_BIOME);
    }

    /**
     * Get {@link BCLBiome} from given {@link Biome}.
     *
     * @param biome - biome {@link Biome}.
     * @return {@link BCLBiome} or {@code BiomeAPI.EMPTY_BIOME}.
     */
    public static BCLBiome getBiome(Biome biome) {
        return getBiome(BiomeAPI.getBiomeID(biome));
    }

    /**
     * Get {@link BCLBiome} from given {@link Biome}.
     *
     * @param biome - biome {@link Biome}.
     * @return {@link BCLBiome} or {@code BiomeAPI.EMPTY_BIOME}.
     */
    public static BCLBiome getBiome(Holder<Biome> biome) {
        return getBiome(BiomeAPI.getBiomeID(biome));
    }

    /**
     * Check if biome with {@link ResourceLocation} exists in API registry.
     *
     * @param biomeID - biome {@link ResourceLocation}.
     * @return {@code true} if biome exists in API registry and {@code false} if not.
     */
    public static boolean hasBiome(ResourceLocation biomeID) {
        return ID_MAP.containsKey(biomeID);
    }

    /**
     * Load biomes from Fabric API. For internal usage only.
     */
    public static void loadFabricAPIBiomes() {
        FabricBiomesData.NETHER_BIOMES.forEach((key) -> {
            if (!hasBiome(key.location())) {
                Optional<Holder<Biome>> optional = BuiltinRegistries.BIOME.getHolder(key);
                if (optional.isPresent()) {
                    registerNetherBiome(optional.get().value());
                }
            }
        });

        FabricBiomesData.END_LAND_BIOMES.forEach((key, weight) -> {
            if (!hasBiome(key.location())) {
                Optional<Holder<Biome>> optional = BuiltinRegistries.BIOME.getHolder(key);
                if (optional.isPresent()) {
                    registerEndLandBiome(optional.get(), weight);
                }
            }
        });

        FabricBiomesData.END_VOID_BIOMES.forEach((key, weight) -> {
            if (!hasBiome(key.location())) {
                Optional<Holder<Biome>> optional = BuiltinRegistries.BIOME.getHolder(key);
                if (optional.isPresent()) {
                    registerEndVoidBiome(optional.get(), weight);
                }
            }
        });
    }

    @Nullable
    public static Holder<Biome> getFromRegistry(ResourceLocation key) {
        return BuiltinRegistries.BIOME.getHolder(ResourceKey.create(Registry.BIOME_REGISTRY, key)).orElseThrow();
    }

    @Nullable
    public static Holder<Biome> getFromRegistry(ResourceKey<Biome> key) {
        return BuiltinRegistries.BIOME.getOrCreateHolderOrThrow(key);
    }

    public static boolean isDatapackBiome(ResourceLocation biomeID) {
        return getFromRegistry(biomeID) == null;
    }

    public static boolean wasRegisteredAs(ResourceLocation biomeID, BiomeType dim) {
        if (BiomeType.BIOME_TYPE_MAP.containsKey(biomeID) && BiomeType.BIOME_TYPE_MAP.get(biomeID).is(dim)) return true;
        BCLBiome biome = getBiome(biomeID);
        if (biome != null && biome != BiomeAPI.EMPTY_BIOME && biome.getParentBiome() != null) {
            return wasRegisteredAs(biome.getParentBiome().getID(), dim);
        }
        return false;
    }

    public static boolean wasRegisteredAsNetherBiome(ResourceLocation biomeID) {
        return wasRegisteredAs(biomeID, BiomeType.NETHER);
    }

    public static boolean wasRegisteredAsEndBiome(ResourceLocation biomeID) {
        return wasRegisteredAs(biomeID, BiomeType.END);
    }

    public static boolean wasRegisteredAsEndLandBiome(ResourceLocation biomeID) {
        return wasRegisteredAs(biomeID, BiomeType.END_LAND);
    }

    public static boolean wasRegisteredAsEndVoidBiome(ResourceLocation biomeID) {
        return wasRegisteredAs(biomeID, BiomeType.END_VOID);
    }

    /**
     * Registers new biome modification for specified dimension. Will work both for mod and datapack biomes.
     *
     * @param dimensionID  {@link ResourceLocation} dimension ID, example: Level.OVERWORLD or "minecraft:overworld".
     * @param modification {@link BiConsumer} with {@link ResourceKey} biome ID and {@link Biome} parameters.
     */
    public static void registerBiomeModification(ResourceKey<LevelStem> dimensionID,
                                                 BiConsumer<ResourceLocation, Holder<Biome>> modification) {
        List<BiConsumer<ResourceLocation, Holder<Biome>>> modifications = MODIFICATIONS.computeIfAbsent(dimensionID,
                k -> Lists.newArrayList());
        modifications.add(modification);
    }

    /**
     * Registers new biome modification for the Overworld. Will work both for mod and datapack biomes.
     *
     * @param modification {@link BiConsumer} with {@link ResourceLocation} biome ID and {@link Biome} parameters.
     */
    public static void registerOverworldBiomeModification(BiConsumer<ResourceLocation, Holder<Biome>> modification) {
        registerBiomeModification(LevelStem.OVERWORLD, modification);
    }

    /**
     * Registers new biome modification for the Nether. Will work both for mod and datapack biomes.
     *
     * @param modification {@link BiConsumer} with {@link ResourceLocation} biome ID and {@link Biome} parameters.
     */
    public static void registerNetherBiomeModification(BiConsumer<ResourceLocation, Holder<Biome>> modification) {
        registerBiomeModification(LevelStem.NETHER, modification);
    }

    /**
     * Registers new biome modification for the End. Will work both for mod and datapack biomes.
     *
     * @param modification {@link BiConsumer} with {@link ResourceLocation} biome ID and {@link Biome} parameters.
     */
    public static void registerEndBiomeModification(BiConsumer<ResourceLocation, Holder<Biome>> modification) {
        registerBiomeModification(LevelStem.END, modification);
    }

    /**
     * For internal use only
     */
    public static void _runBiomeTagAdders() {
        for (var mod : TAG_ADDERS.entrySet()) {
            Stream<ResourceLocation> s = null;
            if (mod.getKey() == Level.NETHER) s = BiomeType.BIOME_TYPE_MAP.entrySet()
                                                                          .stream()
                                                                          .filter(e -> e.getValue()
                                                                                        .is(BiomeType.NETHER))
                                                                          .map(e -> e.getKey());
            else if (mod.getKey() == Level.END) s = BiomeType.BIOME_TYPE_MAP.entrySet()
                                                                            .stream()
                                                                            .filter(e -> e.getValue().is(BiomeType.END))
                                                                            .map(e -> e.getKey());
            if (s != null) {
                s.forEach(id -> {
                    Holder<Biome> biomeHolder = BiomeAPI.getBiomeHolder(id);
                    if (biomeHolder.isBound()) {
                        mod.getValue().forEach(c -> c.accept(id, biomeHolder));
                    }
                });
            }
        }
    }

    /**
     * Registers new biome modification for specified dimension. Will work both for mod and datapack biomes.
     *
     * @param dimensionID  {@link ResourceLocation} dimension ID, example: Level.OVERWORLD or "minecraft:overworld".
     * @param modification {@link BiConsumer} with {@link ResourceKey} biome ID and {@link Biome} parameters.
     */
    public static void onFinishingBiomeTags(ResourceKey dimensionID,
                                            BiConsumer<ResourceLocation, Holder<Biome>> modification) {
        List<BiConsumer<ResourceLocation, Holder<Biome>>> modifications = TAG_ADDERS.computeIfAbsent(dimensionID,
                k -> Lists.newArrayList());
        modifications.add(modification);
    }

    /**
     * Registers new biome modification for the Nether. Will work both for mod and datapack biomes.
     *
     * @param modification {@link BiConsumer} with {@link ResourceLocation} biome ID and {@link Biome} parameters.
     */
    public static void onFinishingNetherBiomeTags(BiConsumer<ResourceLocation, Holder<Biome>> modification) {
        onFinishingBiomeTags(Level.NETHER, modification);
    }

    /**
     * Registers new biome modification for the End. Will work both for mod and datapack biomes.
     *
     * @param modification {@link BiConsumer} with {@link ResourceLocation} biome ID and {@link Biome} parameters.
     */
    public static void onFinishingEndBiomeTags(BiConsumer<ResourceLocation, Holder<Biome>> modification) {
        onFinishingBiomeTags(Level.END, modification);
    }

    /**
     * Will apply biome modiffications to world, internal usage only.
     *
     * @param level
     */
    @Deprecated(forRemoval = true)
    public static void applyModificationsDeprecated(ServerLevel level) {
        //TODO: Now Disabled, because we fix the settings when everything gets loaded
        if (level != null) return;

        NoiseGeneratorSettings noiseGeneratorSettings = null;
        final ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        final BiomeSource source = chunkGenerator.getBiomeSource();
        final Set<Holder<Biome>> biomes = source.possibleBiomes();

        if (chunkGenerator instanceof NoiseGeneratorSettingsProvider gen)
            noiseGeneratorSettings = gen.bclib_getNoiseGeneratorSettings();

        // Datapacks (like Amplified Nether)will change the GeneratorSettings upon load, so we will
        // only use the default Setting for Nether/End if we were unable to find a settings object
        if (noiseGeneratorSettings == null) {
            if (level.dimension() == Level.NETHER) {
                noiseGeneratorSettings = BuiltinRegistries.NOISE_GENERATOR_SETTINGS.get(NoiseGeneratorSettings.NETHER);
            } else if (level.dimension() == Level.END) {
                noiseGeneratorSettings = BuiltinRegistries.NOISE_GENERATOR_SETTINGS.get(NoiseGeneratorSettings.END);
            }
        }

        List<BiConsumer<ResourceLocation, Holder<Biome>>> modifications = MODIFICATIONS.get(level
                .dimensionTypeRegistration()
                .unwrapKey()
                .orElseThrow());
        for (Holder<Biome> biomeHolder : biomes) {
            if (biomeHolder.isBound()) {
                applyModificationsAndUpdateFeatures(modifications, biomeHolder);
            }
        }


        if (noiseGeneratorSettings != null) {
            final SurfaceRuleProvider provider = SurfaceRuleProvider.class.cast(noiseGeneratorSettings);
            // Multiple Biomes can use the same generator. So we need to keep track of all Biomes that are
            // Provided by all the BiomeSources that use the same generator.
            // This happens for example when using the MiningDimensions, which reuses the generator for the
            // Nethering Dimension
            //MODIFIED_SURFACE_PROVIDERS.add(provider);
            provider.bclib_addBiomeSource(source);
        } else {
            BCLib.LOGGER.warning("No generator for " + source);
        }

        ((BiomeSourceAccessor) source).bclRebuildFeatures();
    }

    public static void applyModifications(BiomeSource source, ResourceKey<LevelStem> dimension) {
        BCLib.LOGGER.info("Apply Modifications for " + dimension.location() + " BiomeSource " + source);
        final Set<Holder<Biome>> biomes = source.possibleBiomes();
        List<BiConsumer<ResourceLocation, Holder<Biome>>> modifications = MODIFICATIONS.get(dimension);
        for (Holder<Biome> biomeHolder : biomes) {
            if (biomeHolder.isBound()) {
                applyModificationsAndUpdateFeatures(modifications, biomeHolder);
            }
        }
    }


    private static void applyModificationsAndUpdateFeatures(List<BiConsumer<ResourceLocation, Holder<Biome>>> modifications,
                                                            Holder<Biome> biome) {
        ResourceLocation biomeID = getBiomeID(biome);
        if (modifications != null) {
            modifications.forEach(consumer -> {
                consumer.accept(biomeID, biome);
            });
        }

        sortBiomeFeatures(biome);
    }

    /**
     * Create a unique sort order for all Features of the Biome
     *
     * @param biome The {@link Biome} to sort the features for
     */
    public static void sortBiomeFeatures(Holder<Biome> biome) {
        BiomeGenerationSettings settings = biome.value().getGenerationSettings();
        BiomeGenerationSettingsAccessor accessor = (BiomeGenerationSettingsAccessor) settings;
        List<HolderSet<PlacedFeature>> featureList = CollectionsUtil.getMutable(accessor.bclib_getFeatures());
        final int size = featureList.size();
        for (int i = 0; i < size; i++) {
            List<Holder<PlacedFeature>> features = getFeaturesListCopy(featureList, i);
            sortFeatures(features);
            featureList.set(i, HolderSet.direct(features));
        }
        accessor.bclib_setFeatures(featureList);
    }

    /**
     * Adds new features to existing biome.
     *
     * @param biome   {@link Biome} to add features in.
     * @param feature {@link ConfiguredFeature} to add.
     */
    public static void addBiomeFeature(Holder<Biome> biome, BCLFeature feature) {
        addBiomeFeature(biome, feature.getDecoration(), feature.getPlacedFeature());
    }

    /**
     * Adds new features to existing biome.
     *
     * @param biome       {@link Biome} to add features in.
     * @param step        a {@link Decoration} step for the feature.
     * @param featureList {@link ConfiguredFeature} to add.
     */
    public static void addBiomeFeature(Holder<Biome> biome, Decoration step, Holder<PlacedFeature>... featureList) {
        addBiomeFeature(biome, step, List.of(featureList));
    }

    /**
     * Adds new features to existing biome.
     *
     * @param biome              {@link Biome} to add features in.
     * @param step               a {@link Decoration} step for the feature.
     * @param additionalFeatures List of {@link ConfiguredFeature} to add.
     */
    private static void addBiomeFeature(Holder<Biome> biome,
                                        Decoration step,
                                        List<Holder<PlacedFeature>> additionalFeatures) {
        BiomeGenerationSettingsAccessor accessor = (BiomeGenerationSettingsAccessor) biome.value()
                                                                                          .getGenerationSettings();
        List<HolderSet<PlacedFeature>> allFeatures = CollectionsUtil.getMutable(accessor.bclib_getFeatures());
        List<Holder<PlacedFeature>> features = getFeaturesListCopy(allFeatures, step);

        for (var feature : additionalFeatures) {
            if (!features.contains(feature))
                features.add(feature);
        }

        allFeatures.set(step.ordinal(), HolderSet.direct(features));
        final Supplier<List<ConfiguredFeature<?, ?>>> flowerFeatures = Suppliers.memoize(() -> allFeatures.stream()
                                                                                                          .flatMap(
                                                                                                                  HolderSet::stream)
                                                                                                          .map(Holder::value)
                                                                                                          .flatMap(
                                                                                                                  PlacedFeature::getFeatures)
                                                                                                          .filter(configuredFeature -> configuredFeature.feature() == Feature.FLOWER)
                                                                                                          .collect(
                                                                                                                  ImmutableList.toImmutableList()));
        final Supplier<Set<PlacedFeature>> featureSet = Suppliers.memoize(() -> allFeatures.stream()
                                                                                           .flatMap(HolderSet::stream)
                                                                                           .map(Holder::value)
                                                                                           .collect(Collectors.toSet()));

        accessor.bclib_setFeatures(allFeatures);
        accessor.bclib_setFeatureSet(featureSet);
        accessor.bclib_setFlowerFeatures(flowerFeatures);
    }


    /**
     * Adds mob spawning to specified biome.
     *
     * @param biome         {@link Biome} to add mob spawning.
     * @param entityType    {@link EntityType} mob type.
     * @param weight        spawn weight.
     * @param minGroupCount minimum mobs in group.
     * @param maxGroupCount maximum mobs in group.
     */
    public static <M extends Mob> void addBiomeMobSpawn(Holder<Biome> biome,
                                                        EntityType<M> entityType,
                                                        int weight,
                                                        int minGroupCount,
                                                        int maxGroupCount) {
        final MobCategory category = entityType.getCategory();
        MobSpawnSettingsAccessor accessor = (MobSpawnSettingsAccessor) biome.value().getMobSettings();
        Map<MobCategory, WeightedRandomList<SpawnerData>> spawners = CollectionsUtil.getMutable(accessor.bcl_getSpawners());
        List<SpawnerData> mobs = spawners.containsKey(category)
                ? CollectionsUtil.getMutable(spawners.get(category)
                                                     .unwrap())
                : Lists.newArrayList();
        mobs.add(new SpawnerData(entityType, weight, minGroupCount, maxGroupCount));
        spawners.put(category, WeightedRandomList.create(mobs));
        accessor.bcl_setSpawners(spawners);
    }


    public static Optional<BlockState> findTopMaterial(WorldGenLevel world, BlockPos pos) {
        return findTopMaterial(getBiome(world.getBiome(pos)));
    }

    public static Optional<BlockState> findTopMaterial(Holder<Biome> biome) {
        return findTopMaterial(getBiome(biome.value()));
    }

    public static Optional<BlockState> findTopMaterial(Biome biome) {
        return findTopMaterial(getBiome(biome));
    }

    public static Optional<BlockState> findTopMaterial(BCLBiome biome) {
        if (biome instanceof SurfaceMaterialProvider smp) {
            return Optional.of(smp.getTopMaterial());
        }
        return Optional.empty();
    }

    public static Optional<BlockState> findUnderMaterial(Holder<Biome> biome) {
        return findUnderMaterial(getBiome(biome.value()));
    }

    public static Optional<BlockState> findUnderMaterial(BCLBiome biome) {
        if (biome instanceof SurfaceMaterialProvider smp) {
            return Optional.of(smp.getUnderMaterial());
        }
        return Optional.empty();
    }

    /**
     * Set biome in chunk at specified position.
     *
     * @param chunk {@link ChunkAccess} chunk to set biome in.
     * @param pos   {@link BlockPos} biome position.
     * @param biome {@link Holder<Biome>} instance. Should be biome from world.
     */
    public static void setBiome(ChunkAccess chunk, BlockPos pos, Holder<Biome> biome) {
        int sectionY = (pos.getY() - chunk.getMinBuildHeight()) >> 4;
        PalettedContainerRO<Holder<Biome>> biomes = chunk.getSection(sectionY).getBiomes();
        //TODO: 1.19 This will disable cave generation the end, Structure was made RO, so we have to find another way to handle this
        //biomes.set((pos.getX() & 15) >> 2, (pos.getY() & 15) >> 2, (pos.getZ() & 15) >> 2, biome);
        BCLib.LOGGER.warning("Unable to change Biome at " + pos);
    }

    /**
     * Set biome in world at specified position.
     *
     * @param level {@link LevelAccessor} world to set biome in.
     * @param pos   {@link BlockPos} biome position.
     * @param biome {@link Holder<Biome>} instance. Should be biome from world.
     */
    public static void setBiome(LevelAccessor level, BlockPos pos, Holder<Biome> biome) {
        ChunkAccess chunk = level.getChunk(pos);
        setBiome(chunk, pos, biome);
    }

    private static void sortFeatures(List<Holder<PlacedFeature>> features) {
        initFeatureOrder();

        Set<Holder<PlacedFeature>> featuresWithoutDuplicates = Sets.newHashSet();
        features.forEach(holder -> featuresWithoutDuplicates.add(holder));

        if (featuresWithoutDuplicates.size() != features.size()) {
            features.clear();
            featuresWithoutDuplicates.forEach(feature -> features.add(feature));
        }

        features.forEach(feature -> {
            FEATURE_ORDER.computeIfAbsent(feature, f -> FEATURE_ORDER_ID.getAndIncrement());
        });

        features.sort((f1, f2) -> {
            int v1 = FEATURE_ORDER.getOrDefault(f1, 70000);
            int v2 = FEATURE_ORDER.getOrDefault(f2, 70000);
            return Integer.compare(v1, v2);
        });
    }


    private static List<Holder<PlacedFeature>> getFeaturesListCopy(List<HolderSet<PlacedFeature>> features,
                                                                   Decoration step) {
        return getFeaturesListCopy(features, step.ordinal());
    }

    private static List<Holder<PlacedFeature>> getFeaturesListCopy(List<HolderSet<PlacedFeature>> features, int index) {
        while (features.size() <= index) {
            features.add(HolderSet.direct(Lists.newArrayList()));
        }
        return features.get(index).stream().collect(Collectors.toList());
    }
}