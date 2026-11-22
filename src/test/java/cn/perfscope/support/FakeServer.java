/*
 * PerfScope - Minecraft server performance monitoring plugin
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.support;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;

import java.util.List;
import java.util.Set;

/**
 * 测试用最小 {@link Server} 实现：<b>只</b>回答 {@link #getMessenger()}，其余一律抛
 * {@link UnsupportedOperationException}。
 *
 * <h2>为什么需要这个桩，而不是 {@code Proxy.newProxyInstance(Server.class, …)}</h2>
 *
 * <p>{@code KNOWN-TEST-LIMITS.md} 第 1 条记录过"造不出假 {@code Server}"。本类把那句话推进了一步：
 * 那条结论对 <b>{@code Proxy}</b> 成立，对<b>具体实现</b>不成立——这正是该文件第 1 条
 * "如果将来要解，按这个顺序"里的第 3 条路径。</p>
 *
 * <p><b>实测（本仓库 JDK 17，即 surefire 实际使用的 JVM）</b>：
 * {@code Proxy.newProxyInstance(Server.class, …)} 抛
 * {@code ExceptionInInitializerError → NullPointerException: Bukkit.server is null}。栈是</p>
 * <pre>
 * org.bukkit.generator.structure.StructureType.&lt;clinit&gt;
 *   ← jdk.proxy2.$Proxy13.&lt;clinit&gt;
 *   ← java.lang.reflect.Proxy.newProxyInstance
 * </pre>
 * <p>根因：{@code Proxy} 生成的类在 <b>{@code <clinit>}</b> 里 {@code Class.forName} 解析
 * {@code Server} 全部方法的参数/返回类型，而 {@code Server} 上有
 * {@code createExplorerMap(World, Location, StructureType, …)}；{@code StructureType.<clinit>}
 * 第一件事就是问 {@code Bukkit.server.getRegistry(...)}。那是 {@code Proxy} 的实现细节，
 * 测试里没有可绕开的开关。</p>
 *
 * <p><b>JDK 21 上同一个 {@code Proxy} 调用是成功的</b>（实测），所以"能不能用 Proxy 造 Server"
 * 会随 JVM 版本变化——把测试结论押在它上面本来就不该做。本机 Maven 跑的是 JDK 17
 * （`mvn -version` 实测：Microsoft OpenJDK 17.0.20.1），即 CI 与本地同一套口径。</p>
 *
 * <h2>为什么这个桩绕开了 {@code StructureType}</h2>
 *
 * <p>{@code implements Server} 在<b>编译期</b>要解析全部方法签名（所以本类的方法清单是从
 * {@code Server.class.getMethods()} 生成的，手写不现实），但<b>运行期加载本类不会初始化
 * {@code StructureType}</b>——JLS 12.4.1 只在"首次主动使用"时初始化，而<b>声明</b>一个参数类型
 * 不算主动使用。实测：{@code new FakeServer(null)} 在测试 JVM 里成功，且全程不碰
 * {@code Bukkit.server}。</p>
 *
 * <h2>维护约定</h2>
 *
 * <p>方法清单来自 {@code paper-api 1.20.4}。升级 paper-api 后若 {@code Server} 新增抽象方法，
 * 本类会<b>编译失败</b>（而不是运行期 {@code AbstractMethodError}）——这正是想要的信号。
 * 补法：照下面三条既有规则加一个方法——抽象方法抛 {@code UnsupportedOperationException}；
 * {@code getMessenger()} 返回注入值；{@code PluginMessageRecipient} /
 * {@code ForwardingAudience} 的抽象方法给空实现。</p>
 *
 * <p><b>只给测试用</b>：它在 test 源码树里，不会进 shade 产物。</p>
 */
@SuppressWarnings({"removal", "deprecation", "unused"})
public final class FakeServer implements Server {

    /** 可变字段：让同一个 publisher 能"先失败后成功"（{@code register()} 的清场逻辑要这样测）。 */
    private Messenger messenger;

    public FakeServer(Messenger messenger) {
        this.messenger = messenger;
    }

    public void setMessenger(Messenger messenger) {
        this.messenger = messenger;
    }

    @Override
    public Messenger getMessenger() {
        return messenger;
    }

    @Override
    public Set<String> getListeningPluginChannels() {
        return Set.of();
    }

    @Override
    public void sendPluginMessage(Plugin source, String channel, byte[] message) {
        throw new UnsupportedOperationException("sendPluginMessage");
    }

    @Override
    public Iterable<? extends net.kyori.adventure.audience.Audience> audiences() {
        return List.of();
    }

    @Override public boolean addRecipe(org.bukkit.inventory.Recipe a0) {
        throw new UnsupportedOperationException("addRecipe");
    }
    @Override public boolean addRecipe(org.bukkit.inventory.Recipe a0, boolean a1) {
        throw new UnsupportedOperationException("addRecipe");
    }
    @Override public java.util.Iterator<org.bukkit.advancement.Advancement> advancementIterator() {
        throw new UnsupportedOperationException("advancementIterator");
    }
    @Override public void banIP(java.lang.String a0) {
        throw new UnsupportedOperationException("banIP");
    }
    @Override public void banIP(java.net.InetAddress a0) {
        throw new UnsupportedOperationException("banIP");
    }
    @Override public int broadcast(java.lang.String a0, java.lang.String a1) {
        throw new UnsupportedOperationException("broadcast");
    }
    @Override public int broadcast(net.kyori.adventure.text.Component a0) {
        throw new UnsupportedOperationException("broadcast");
    }
    @Override public int broadcast(net.kyori.adventure.text.Component a0, java.lang.String a1) {
        throw new UnsupportedOperationException("broadcast");
    }
    @Override public int broadcastMessage(java.lang.String a0) {
        throw new UnsupportedOperationException("broadcastMessage");
    }
    @Override public void clearRecipes() {
        throw new UnsupportedOperationException("clearRecipes");
    }
    @Override public org.bukkit.inventory.ItemStack craftItem(org.bukkit.inventory.ItemStack[] a0, org.bukkit.World a1) {
        throw new UnsupportedOperationException("craftItem");
    }
    @Override public org.bukkit.inventory.ItemStack craftItem(org.bukkit.inventory.ItemStack[] a0, org.bukkit.World a1, org.bukkit.entity.Player a2) {
        throw new UnsupportedOperationException("craftItem");
    }
    @Override public org.bukkit.inventory.ItemCraftResult craftItemResult(org.bukkit.inventory.ItemStack[] a0, org.bukkit.World a1) {
        throw new UnsupportedOperationException("craftItemResult");
    }
    @Override public org.bukkit.inventory.ItemCraftResult craftItemResult(org.bukkit.inventory.ItemStack[] a0, org.bukkit.World a1, org.bukkit.entity.Player a2) {
        throw new UnsupportedOperationException("craftItemResult");
    }
    @Override public org.bukkit.block.data.BlockData createBlockData(java.lang.String a0) {
        throw new UnsupportedOperationException("createBlockData");
    }
    @Override public org.bukkit.block.data.BlockData createBlockData(org.bukkit.Material a0) {
        throw new UnsupportedOperationException("createBlockData");
    }
    @Override public org.bukkit.block.data.BlockData createBlockData(org.bukkit.Material a0, java.lang.String a1) {
        throw new UnsupportedOperationException("createBlockData");
    }
    @Override public org.bukkit.block.data.BlockData createBlockData(org.bukkit.Material a0, java.util.function.Consumer<? super org.bukkit.block.data.BlockData> a1) {
        throw new UnsupportedOperationException("createBlockData");
    }
    @Override public org.bukkit.boss.BossBar createBossBar(java.lang.String a0, org.bukkit.boss.BarColor a1, org.bukkit.boss.BarStyle a2, org.bukkit.boss.BarFlag[] a3) {
        throw new UnsupportedOperationException("createBossBar");
    }
    @Override public org.bukkit.boss.KeyedBossBar createBossBar(org.bukkit.NamespacedKey a0, java.lang.String a1, org.bukkit.boss.BarColor a2, org.bukkit.boss.BarStyle a3, org.bukkit.boss.BarFlag[] a4) {
        throw new UnsupportedOperationException("createBossBar");
    }
    @Override public org.bukkit.generator.ChunkGenerator.ChunkData createChunkData(org.bukkit.World a0) {
        throw new UnsupportedOperationException("createChunkData");
    }
    @Override public org.bukkit.command.CommandSender createCommandSender(java.util.function.Consumer<? super net.kyori.adventure.text.Component> a0) {
        throw new UnsupportedOperationException("createCommandSender");
    }
    @Override public org.bukkit.inventory.ItemStack createExplorerMap(org.bukkit.World a0, org.bukkit.Location a1, org.bukkit.StructureType a2) {
        throw new UnsupportedOperationException("createExplorerMap");
    }
    @Override public org.bukkit.inventory.ItemStack createExplorerMap(org.bukkit.World a0, org.bukkit.Location a1, org.bukkit.StructureType a2, int a3, boolean a4) {
        throw new UnsupportedOperationException("createExplorerMap");
    }
    @Override public org.bukkit.inventory.ItemStack createExplorerMap(org.bukkit.World a0, org.bukkit.Location a1, org.bukkit.generator.structure.StructureType a2, org.bukkit.map.MapCursor.Type a3, int a4, boolean a5) {
        throw new UnsupportedOperationException("createExplorerMap");
    }
    @Override public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder a0, int a1) {
        throw new UnsupportedOperationException("createInventory");
    }
    @Override public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder a0, int a1, java.lang.String a2) {
        throw new UnsupportedOperationException("createInventory");
    }
    @Override public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder a0, int a1, net.kyori.adventure.text.Component a2) {
        throw new UnsupportedOperationException("createInventory");
    }
    @Override public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder a0, org.bukkit.event.inventory.InventoryType a1) {
        throw new UnsupportedOperationException("createInventory");
    }
    @Override public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder a0, org.bukkit.event.inventory.InventoryType a1, java.lang.String a2) {
        throw new UnsupportedOperationException("createInventory");
    }
    @Override public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder a0, org.bukkit.event.inventory.InventoryType a1, net.kyori.adventure.text.Component a2) {
        throw new UnsupportedOperationException("createInventory");
    }
    @Override public org.bukkit.map.MapView createMap(org.bukkit.World a0) {
        throw new UnsupportedOperationException("createMap");
    }
    @Override public org.bukkit.inventory.Merchant createMerchant(java.lang.String a0) {
        throw new UnsupportedOperationException("createMerchant");
    }
    @Override public org.bukkit.inventory.Merchant createMerchant(net.kyori.adventure.text.Component a0) {
        throw new UnsupportedOperationException("createMerchant");
    }
    @Override public org.bukkit.profile.PlayerProfile createPlayerProfile(java.lang.String a0) {
        throw new UnsupportedOperationException("createPlayerProfile");
    }
    @Override public org.bukkit.profile.PlayerProfile createPlayerProfile(java.util.UUID a0) {
        throw new UnsupportedOperationException("createPlayerProfile");
    }
    @Override public org.bukkit.profile.PlayerProfile createPlayerProfile(java.util.UUID a0, java.lang.String a1) {
        throw new UnsupportedOperationException("createPlayerProfile");
    }
    @Override public com.destroystokyo.paper.profile.PlayerProfile createProfile(java.lang.String a0) {
        throw new UnsupportedOperationException("createProfile");
    }
    @Override public com.destroystokyo.paper.profile.PlayerProfile createProfile(java.util.UUID a0) {
        throw new UnsupportedOperationException("createProfile");
    }
    @Override public com.destroystokyo.paper.profile.PlayerProfile createProfile(java.util.UUID a0, java.lang.String a1) {
        throw new UnsupportedOperationException("createProfile");
    }
    @Override public com.destroystokyo.paper.profile.PlayerProfile createProfileExact(java.util.UUID a0, java.lang.String a1) {
        throw new UnsupportedOperationException("createProfileExact");
    }
    @Override public org.bukkit.generator.ChunkGenerator.ChunkData createVanillaChunkData(org.bukkit.World a0, int a1, int a2) {
        throw new UnsupportedOperationException("createVanillaChunkData");
    }
    @Override public org.bukkit.World createWorld(org.bukkit.WorldCreator a0) {
        throw new UnsupportedOperationException("createWorld");
    }
    @Override public org.bukkit.WorldBorder createWorldBorder() {
        throw new UnsupportedOperationException("createWorldBorder");
    }
    @Override public boolean dispatchCommand(org.bukkit.command.CommandSender a0, java.lang.String a1) {
        throw new UnsupportedOperationException("dispatchCommand");
    }
    @Override public org.bukkit.advancement.Advancement getAdvancement(org.bukkit.NamespacedKey a0) {
        throw new UnsupportedOperationException("getAdvancement");
    }
    @Override public boolean getAllowEnd() {
        throw new UnsupportedOperationException("getAllowEnd");
    }
    @Override public boolean getAllowFlight() {
        throw new UnsupportedOperationException("getAllowFlight");
    }
    @Override public boolean getAllowNether() {
        throw new UnsupportedOperationException("getAllowNether");
    }
    @Override public int getAmbientSpawnLimit() {
        throw new UnsupportedOperationException("getAmbientSpawnLimit");
    }
    @Override public int getAnimalSpawnLimit() {
        throw new UnsupportedOperationException("getAnimalSpawnLimit");
    }
    @Override public io.papermc.paper.threadedregions.scheduler.AsyncScheduler getAsyncScheduler() {
        throw new UnsupportedOperationException("getAsyncScheduler");
    }
    @Override public double getAverageTickTime() {
        throw new UnsupportedOperationException("getAverageTickTime");
    }
    @Override public <B extends org.bukkit.BanList<E>, E> B getBanList(io.papermc.paper.ban.BanListType<B> a0) {
        throw new UnsupportedOperationException("getBanList");
    }
    @Override public <T extends org.bukkit.BanList<?>> T getBanList(org.bukkit.BanList.Type a0) {
        throw new UnsupportedOperationException("getBanList");
    }
    @Override public java.util.Set<org.bukkit.OfflinePlayer> getBannedPlayers() {
        throw new UnsupportedOperationException("getBannedPlayers");
    }
    @Override public org.bukkit.boss.KeyedBossBar getBossBar(org.bukkit.NamespacedKey a0) {
        throw new UnsupportedOperationException("getBossBar");
    }
    @Override public java.util.Iterator<org.bukkit.boss.KeyedBossBar> getBossBars() {
        throw new UnsupportedOperationException("getBossBars");
    }
    @Override public java.lang.String getBukkitVersion() {
        throw new UnsupportedOperationException("getBukkitVersion");
    }
    @Override public java.util.Map<java.lang.String, java.lang.String[]> getCommandAliases() {
        throw new UnsupportedOperationException("getCommandAliases");
    }
    @Override public org.bukkit.command.CommandMap getCommandMap() {
        throw new UnsupportedOperationException("getCommandMap");
    }
    @Override public long getConnectionThrottle() {
        throw new UnsupportedOperationException("getConnectionThrottle");
    }
    @Override public org.bukkit.command.ConsoleCommandSender getConsoleSender() {
        throw new UnsupportedOperationException("getConsoleSender");
    }
    @Override public org.bukkit.inventory.Recipe getCraftingRecipe(org.bukkit.inventory.ItemStack[] a0, org.bukkit.World a1) {
        throw new UnsupportedOperationException("getCraftingRecipe");
    }
    @Override public int getCurrentTick() {
        throw new UnsupportedOperationException("getCurrentTick");
    }
    @Override public org.bukkit.packs.DataPackManager getDataPackManager() {
        throw new UnsupportedOperationException("getDataPackManager");
    }
    @Override public io.papermc.paper.datapack.DatapackManager getDatapackManager() {
        throw new UnsupportedOperationException("getDatapackManager");
    }
    @Override public org.bukkit.GameMode getDefaultGameMode() {
        throw new UnsupportedOperationException("getDefaultGameMode");
    }
    @Override public org.bukkit.entity.Entity getEntity(java.util.UUID a0) {
        throw new UnsupportedOperationException("getEntity");
    }
    @Override public boolean getGenerateStructures() {
        throw new UnsupportedOperationException("getGenerateStructures");
    }
    @Override public io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler getGlobalRegionScheduler() {
        throw new UnsupportedOperationException("getGlobalRegionScheduler");
    }
    @Override public org.bukkit.help.HelpMap getHelpMap() {
        throw new UnsupportedOperationException("getHelpMap");
    }
    @Override public boolean getHideOnlinePlayers() {
        throw new UnsupportedOperationException("getHideOnlinePlayers");
    }
    @Override public java.util.Set<java.lang.String> getIPBans() {
        throw new UnsupportedOperationException("getIPBans");
    }
    @Override public int getIdleTimeout() {
        throw new UnsupportedOperationException("getIdleTimeout");
    }
    @Override public java.util.List<java.lang.String> getInitialDisabledPacks() {
        throw new UnsupportedOperationException("getInitialDisabledPacks");
    }
    @Override public java.util.List<java.lang.String> getInitialEnabledPacks() {
        throw new UnsupportedOperationException("getInitialEnabledPacks");
    }
    @Override public java.lang.String getIp() {
        throw new UnsupportedOperationException("getIp");
    }
    @Override public org.bukkit.inventory.ItemFactory getItemFactory() {
        throw new UnsupportedOperationException("getItemFactory");
    }
    @Override public java.util.logging.Logger getLogger() {
        throw new UnsupportedOperationException("getLogger");
    }
    @Override public org.bukkit.loot.LootTable getLootTable(org.bukkit.NamespacedKey a0) {
        throw new UnsupportedOperationException("getLootTable");
    }
    @Override public org.bukkit.map.MapView getMap(int a0) {
        throw new UnsupportedOperationException("getMap");
    }
    @Override public int getMaxChainedNeighborUpdates() {
        throw new UnsupportedOperationException("getMaxChainedNeighborUpdates");
    }
    @Override public int getMaxPlayers() {
        throw new UnsupportedOperationException("getMaxPlayers");
    }
    @Override public int getMaxWorldSize() {
        throw new UnsupportedOperationException("getMaxWorldSize");
    }
    @Override public java.lang.String getMinecraftVersion() {
        throw new UnsupportedOperationException("getMinecraftVersion");
    }
    @Override public com.destroystokyo.paper.entity.ai.MobGoals getMobGoals() {
        throw new UnsupportedOperationException("getMobGoals");
    }
    @Override public int getMonsterSpawnLimit() {
        throw new UnsupportedOperationException("getMonsterSpawnLimit");
    }
    @Override public java.lang.String getMotd() {
        throw new UnsupportedOperationException("getMotd");
    }
    @Override public java.lang.String getName() {
        throw new UnsupportedOperationException("getName");
    }
    @Override public org.bukkit.OfflinePlayer getOfflinePlayer(java.lang.String a0) {
        throw new UnsupportedOperationException("getOfflinePlayer");
    }
    @Override public org.bukkit.OfflinePlayer getOfflinePlayer(java.util.UUID a0) {
        throw new UnsupportedOperationException("getOfflinePlayer");
    }
    @Override public org.bukkit.OfflinePlayer getOfflinePlayerIfCached(java.lang.String a0) {
        throw new UnsupportedOperationException("getOfflinePlayerIfCached");
    }
    @Override public org.bukkit.OfflinePlayer[] getOfflinePlayers() {
        throw new UnsupportedOperationException("getOfflinePlayers");
    }
    @Override public boolean getOnlineMode() {
        throw new UnsupportedOperationException("getOnlineMode");
    }
    @Override public java.util.Collection<? extends org.bukkit.entity.Player> getOnlinePlayers() {
        throw new UnsupportedOperationException("getOnlinePlayers");
    }
    @Override public java.util.Set<org.bukkit.OfflinePlayer> getOperators() {
        throw new UnsupportedOperationException("getOperators");
    }
    @Override public java.lang.String getPermissionMessage() {
        throw new UnsupportedOperationException("getPermissionMessage");
    }
    @Override public org.bukkit.entity.Player getPlayer(java.lang.String a0) {
        throw new UnsupportedOperationException("getPlayer");
    }
    @Override public org.bukkit.entity.Player getPlayer(java.util.UUID a0) {
        throw new UnsupportedOperationException("getPlayer");
    }
    @Override public org.bukkit.entity.Player getPlayerExact(java.lang.String a0) {
        throw new UnsupportedOperationException("getPlayerExact");
    }
    @Override public java.util.UUID getPlayerUniqueId(java.lang.String a0) {
        throw new UnsupportedOperationException("getPlayerUniqueId");
    }
    @Override public org.bukkit.command.PluginCommand getPluginCommand(java.lang.String a0) {
        throw new UnsupportedOperationException("getPluginCommand");
    }
    @Override public org.bukkit.plugin.PluginManager getPluginManager() {
        throw new UnsupportedOperationException("getPluginManager");
    }
    @Override public java.io.File getPluginsFolder() {
        throw new UnsupportedOperationException("getPluginsFolder");
    }
    @Override public int getPort() {
        throw new UnsupportedOperationException("getPort");
    }
    @Override public org.bukkit.potion.PotionBrewer getPotionBrewer() {
        throw new UnsupportedOperationException("getPotionBrewer");
    }
    @Override public org.bukkit.inventory.Recipe getRecipe(org.bukkit.NamespacedKey a0) {
        throw new UnsupportedOperationException("getRecipe");
    }
    @Override public java.util.List<org.bukkit.inventory.Recipe> getRecipesFor(org.bukkit.inventory.ItemStack a0) {
        throw new UnsupportedOperationException("getRecipesFor");
    }
    @Override public io.papermc.paper.threadedregions.scheduler.RegionScheduler getRegionScheduler() {
        throw new UnsupportedOperationException("getRegionScheduler");
    }
    @Override public <T extends org.bukkit.Keyed> org.bukkit.Registry<T> getRegistry(java.lang.Class<T> a0) {
        throw new UnsupportedOperationException("getRegistry");
    }
    @Override public java.lang.String getResourcePack() {
        throw new UnsupportedOperationException("getResourcePack");
    }
    @Override public java.lang.String getResourcePackHash() {
        throw new UnsupportedOperationException("getResourcePackHash");
    }
    @Override public java.lang.String getResourcePackPrompt() {
        throw new UnsupportedOperationException("getResourcePackPrompt");
    }
    @Override public org.bukkit.scheduler.BukkitScheduler getScheduler() {
        throw new UnsupportedOperationException("getScheduler");
    }
    @Override public org.bukkit.scoreboard.Criteria getScoreboardCriteria(java.lang.String a0) {
        throw new UnsupportedOperationException("getScoreboardCriteria");
    }
    @Override public org.bukkit.scoreboard.ScoreboardManager getScoreboardManager() {
        throw new UnsupportedOperationException("getScoreboardManager");
    }
    @Override public org.bukkit.util.CachedServerIcon getServerIcon() {
        throw new UnsupportedOperationException("getServerIcon");
    }
    @Override public org.bukkit.packs.ResourcePack getServerResourcePack() {
        throw new UnsupportedOperationException("getServerResourcePack");
    }
    @Override public org.bukkit.ServerTickManager getServerTickManager() {
        throw new UnsupportedOperationException("getServerTickManager");
    }
    @Override public org.bukkit.plugin.ServicesManager getServicesManager() {
        throw new UnsupportedOperationException("getServicesManager");
    }
    @Override public java.lang.String getShutdownMessage() {
        throw new UnsupportedOperationException("getShutdownMessage");
    }
    @Override public int getSimulationDistance() {
        throw new UnsupportedOperationException("getSimulationDistance");
    }
    @Override public int getSpawnLimit(org.bukkit.entity.SpawnCategory a0) {
        throw new UnsupportedOperationException("getSpawnLimit");
    }
    @Override public int getSpawnRadius() {
        throw new UnsupportedOperationException("getSpawnRadius");
    }
    @Override public org.bukkit.structure.StructureManager getStructureManager() {
        throw new UnsupportedOperationException("getStructureManager");
    }
    @Override public double[] getTPS() {
        throw new UnsupportedOperationException("getTPS");
    }
    @Override public <T extends org.bukkit.Keyed> org.bukkit.Tag<T> getTag(java.lang.String a0, org.bukkit.NamespacedKey a1, java.lang.Class<T> a2) {
        throw new UnsupportedOperationException("getTag");
    }
    @Override public <T extends org.bukkit.Keyed> java.lang.Iterable<org.bukkit.Tag<T>> getTags(java.lang.String a0, java.lang.Class<T> a1) {
        throw new UnsupportedOperationException("getTags");
    }
    @Override public long[] getTickTimes() {
        throw new UnsupportedOperationException("getTickTimes");
    }
    @Override public int getTicksPerAmbientSpawns() {
        throw new UnsupportedOperationException("getTicksPerAmbientSpawns");
    }
    @Override public int getTicksPerAnimalSpawns() {
        throw new UnsupportedOperationException("getTicksPerAnimalSpawns");
    }
    @Override public int getTicksPerMonsterSpawns() {
        throw new UnsupportedOperationException("getTicksPerMonsterSpawns");
    }
    @Override public int getTicksPerSpawns(org.bukkit.entity.SpawnCategory a0) {
        throw new UnsupportedOperationException("getTicksPerSpawns");
    }
    @Override public int getTicksPerWaterAmbientSpawns() {
        throw new UnsupportedOperationException("getTicksPerWaterAmbientSpawns");
    }
    @Override public int getTicksPerWaterSpawns() {
        throw new UnsupportedOperationException("getTicksPerWaterSpawns");
    }
    @Override public int getTicksPerWaterUndergroundCreatureSpawns() {
        throw new UnsupportedOperationException("getTicksPerWaterUndergroundCreatureSpawns");
    }
    @Override public org.bukkit.UnsafeValues getUnsafe() {
        throw new UnsupportedOperationException("getUnsafe");
    }
    @Override public java.lang.String getUpdateFolder() {
        throw new UnsupportedOperationException("getUpdateFolder");
    }
    @Override public java.io.File getUpdateFolderFile() {
        throw new UnsupportedOperationException("getUpdateFolderFile");
    }
    @Override public java.lang.String getVersion() {
        throw new UnsupportedOperationException("getVersion");
    }
    @Override public int getViewDistance() {
        throw new UnsupportedOperationException("getViewDistance");
    }
    @Override public org.bukkit.Warning.WarningState getWarningState() {
        throw new UnsupportedOperationException("getWarningState");
    }
    @Override public int getWaterAmbientSpawnLimit() {
        throw new UnsupportedOperationException("getWaterAmbientSpawnLimit");
    }
    @Override public int getWaterAnimalSpawnLimit() {
        throw new UnsupportedOperationException("getWaterAnimalSpawnLimit");
    }
    @Override public int getWaterUndergroundCreatureSpawnLimit() {
        throw new UnsupportedOperationException("getWaterUndergroundCreatureSpawnLimit");
    }
    @Override public java.util.Set<org.bukkit.OfflinePlayer> getWhitelistedPlayers() {
        throw new UnsupportedOperationException("getWhitelistedPlayers");
    }
    @Override public org.bukkit.World getWorld(java.lang.String a0) {
        throw new UnsupportedOperationException("getWorld");
    }
    @Override public org.bukkit.World getWorld(java.util.UUID a0) {
        throw new UnsupportedOperationException("getWorld");
    }
    @Override public org.bukkit.World getWorld(org.bukkit.NamespacedKey a0) {
        throw new UnsupportedOperationException("getWorld");
    }
    @Override public java.io.File getWorldContainer() {
        throw new UnsupportedOperationException("getWorldContainer");
    }
    @Override public java.lang.String getWorldType() {
        throw new UnsupportedOperationException("getWorldType");
    }
    @Override public java.util.List<org.bukkit.World> getWorlds() {
        throw new UnsupportedOperationException("getWorlds");
    }
    @Override public boolean hasWhitelist() {
        throw new UnsupportedOperationException("hasWhitelist");
    }
    @Override public boolean isEnforcingSecureProfiles() {
        throw new UnsupportedOperationException("isEnforcingSecureProfiles");
    }
    @Override public boolean isHardcore() {
        throw new UnsupportedOperationException("isHardcore");
    }
    @Override public boolean isLoggingIPs() {
        throw new UnsupportedOperationException("isLoggingIPs");
    }
    @Override public boolean isOwnedByCurrentRegion(org.bukkit.Location a0) {
        throw new UnsupportedOperationException("isOwnedByCurrentRegion");
    }
    @Override public boolean isOwnedByCurrentRegion(org.bukkit.Location a0, int a1) {
        throw new UnsupportedOperationException("isOwnedByCurrentRegion");
    }
    @Override public boolean isOwnedByCurrentRegion(org.bukkit.World a0, int a1, int a2) {
        throw new UnsupportedOperationException("isOwnedByCurrentRegion");
    }
    @Override public boolean isOwnedByCurrentRegion(org.bukkit.World a0, int a1, int a2, int a3) {
        throw new UnsupportedOperationException("isOwnedByCurrentRegion");
    }
    @Override public boolean isOwnedByCurrentRegion(org.bukkit.World a0, io.papermc.paper.math.Position a1) {
        throw new UnsupportedOperationException("isOwnedByCurrentRegion");
    }
    @Override public boolean isOwnedByCurrentRegion(org.bukkit.World a0, io.papermc.paper.math.Position a1, int a2) {
        throw new UnsupportedOperationException("isOwnedByCurrentRegion");
    }
    @Override public boolean isOwnedByCurrentRegion(org.bukkit.entity.Entity a0) {
        throw new UnsupportedOperationException("isOwnedByCurrentRegion");
    }
    @Override public boolean isPrimaryThread() {
        throw new UnsupportedOperationException("isPrimaryThread");
    }
    @Override public boolean isResourcePackRequired() {
        throw new UnsupportedOperationException("isResourcePackRequired");
    }
    @Override public boolean isStopping() {
        throw new UnsupportedOperationException("isStopping");
    }
    @Override public boolean isTickingWorlds() {
        throw new UnsupportedOperationException("isTickingWorlds");
    }
    @Override public boolean isWhitelistEnforced() {
        throw new UnsupportedOperationException("isWhitelistEnforced");
    }
    @Override public org.bukkit.util.CachedServerIcon loadServerIcon(java.awt.image.BufferedImage a0) {
        throw new UnsupportedOperationException("loadServerIcon");
    }
    @Override public org.bukkit.util.CachedServerIcon loadServerIcon(java.io.File a0) {
        throw new UnsupportedOperationException("loadServerIcon");
    }
    @Override public java.util.List<org.bukkit.entity.Player> matchPlayer(java.lang.String a0) {
        throw new UnsupportedOperationException("matchPlayer");
    }
    @Override public net.kyori.adventure.text.Component motd() {
        throw new UnsupportedOperationException("motd");
    }
    @Override public void motd(net.kyori.adventure.text.Component a0) {
        throw new UnsupportedOperationException("motd");
    }
    @Override public net.kyori.adventure.text.Component permissionMessage() {
        throw new UnsupportedOperationException("permissionMessage");
    }
    @Override public java.util.Iterator<org.bukkit.inventory.Recipe> recipeIterator() {
        throw new UnsupportedOperationException("recipeIterator");
    }
    @Override public void reload() {
        throw new UnsupportedOperationException("reload");
    }
    @Override public boolean reloadCommandAliases() {
        throw new UnsupportedOperationException("reloadCommandAliases");
    }
    @Override public void reloadData() {
        throw new UnsupportedOperationException("reloadData");
    }
    @Override public void reloadPermissions() {
        throw new UnsupportedOperationException("reloadPermissions");
    }
    @Override public void reloadWhitelist() {
        throw new UnsupportedOperationException("reloadWhitelist");
    }
    @Override public boolean removeBossBar(org.bukkit.NamespacedKey a0) {
        throw new UnsupportedOperationException("removeBossBar");
    }
    @Override public boolean removeRecipe(org.bukkit.NamespacedKey a0) {
        throw new UnsupportedOperationException("removeRecipe");
    }
    @Override public boolean removeRecipe(org.bukkit.NamespacedKey a0, boolean a1) {
        throw new UnsupportedOperationException("removeRecipe");
    }
    @Override public void resetRecipes() {
        throw new UnsupportedOperationException("resetRecipes");
    }
    @Override public void savePlayers() {
        throw new UnsupportedOperationException("savePlayers");
    }
    @Override public java.util.List<org.bukkit.entity.Entity> selectEntities(org.bukkit.command.CommandSender a0, java.lang.String a1) {
        throw new UnsupportedOperationException("selectEntities");
    }
    @Override public void setDefaultGameMode(org.bukkit.GameMode a0) {
        throw new UnsupportedOperationException("setDefaultGameMode");
    }
    @Override public void setIdleTimeout(int a0) {
        throw new UnsupportedOperationException("setIdleTimeout");
    }
    @Override public void setMaxPlayers(int a0) {
        throw new UnsupportedOperationException("setMaxPlayers");
    }
    @Override public void setMotd(java.lang.String a0) {
        throw new UnsupportedOperationException("setMotd");
    }
    @Override public void setSpawnRadius(int a0) {
        throw new UnsupportedOperationException("setSpawnRadius");
    }
    @Override public void setWhitelist(boolean a0) {
        throw new UnsupportedOperationException("setWhitelist");
    }
    @Override public void setWhitelistEnforced(boolean a0) {
        throw new UnsupportedOperationException("setWhitelistEnforced");
    }
    @Override public boolean shouldSendChatPreviews() {
        throw new UnsupportedOperationException("shouldSendChatPreviews");
    }
    @Override public void shutdown() {
        throw new UnsupportedOperationException("shutdown");
    }
    @Override public net.kyori.adventure.text.Component shutdownMessage() {
        throw new UnsupportedOperationException("shutdownMessage");
    }
    @Override public org.bukkit.Server.Spigot spigot() {
        throw new UnsupportedOperationException("spigot");
    }
    @Override public boolean suggestPlayerNamesWhenNullTabCompletions() {
        throw new UnsupportedOperationException("suggestPlayerNamesWhenNullTabCompletions");
    }
    @Override public void unbanIP(java.lang.String a0) {
        throw new UnsupportedOperationException("unbanIP");
    }
    @Override public void unbanIP(java.net.InetAddress a0) {
        throw new UnsupportedOperationException("unbanIP");
    }
    @Override public boolean unloadWorld(java.lang.String a0, boolean a1) {
        throw new UnsupportedOperationException("unloadWorld");
    }
    @Override public boolean unloadWorld(org.bukkit.World a0, boolean a1) {
        throw new UnsupportedOperationException("unloadWorld");
    }
    @Override public void updateRecipes() {
        throw new UnsupportedOperationException("updateRecipes");
    }
    @Override public void updateResources() {
        throw new UnsupportedOperationException("updateResources");
    }
}
