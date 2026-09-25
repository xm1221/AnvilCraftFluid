package cn.xm1221.AnvilCraftFluid.fluid

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.damagesource.DamageType

/**
 * 一条**状态效果**要求：站在流体里时持续获得。
 *
 * 用 [ResourceLocation] 而不是 `Holder<MobEffect>` 是**故意的**：
 * [FluidSpec] 是注册期的静态常量（`AddonFluidSpecs` 一被类加载就初始化），
 * 那时候生物效果注册表还没填好，拿 Holder 会直接拿到空值。
 * id 在真正施加时（实体已经在世界里了）才解析，晚一点没关系。
 *
 * @property id 效果 id（如 `minecraft:weakness`）
 * @property durationTicks 每次刷新的持续时间。
 *   **不要写很长**：站在流体里每 tick 都会刷新，短时长（1~3 秒）足够——
 *   这样一离开流体，效果就会自然消失。
 * @property amplifier 等级 - 1（0 = I 级）
 */
data class ContactEffect(
    val id: ResourceLocation,
    val durationTicks: Int,
    val amplifier: Int = 0,
)

/**
 * **站在流体里会受到什么**（世界的流体方块与炼药锅共用同一套）。
 *
 * 全部字段可选，默认 [NONE] 就是"泡在里头什么也不会发生"。想给某种流体加影响，
 * 只在 [FluidSpec.contact] 里写一行即可，不用碰任何注册代码。
 *
 * | 字段 | 效果 |
 * | --- | --- |
 * | [igniteSeconds] | 着火（对齐原版岩浆的 15 秒） |
 * | [damage] / [damageInterval] / [damageType] | 周期性伤害 |
 * | [destroyItems] | 销毁掉进去的**普通**物品（火焰免疫的物品不受影响，见下） |
 * | [freezing] | 像细雪那样逐渐冻住 |
 * | [effects] | 附加状态效果（虚弱 / 凋零……） |
 * | [reforgePerTick] | 反过来的"好事"：用上游的重铸机制**修**掉进来的物品 |
 *
 * ## 为什么销毁物品要跳过火焰免疫的物品
 *
 * 上游让"重铸"装备在岩浆里活下来的办法就是把它做成**火焰免疫**
 * （余烬金属工具"不会被火焰和熔岩摧毁"）。所以这里照抄同一条规矩：
 * 普通物品被烧毁，火焰免疫的物品活着，而带重铸组件的还会被 [reforgePerTick] 修好。
 * 于是"余烬流体烧东西"与"余烬流体修东西"两件事并不冲突。
 *
 * ## 伤害间隔与无敌帧
 *
 * 原版 `Entity#hurt` 有 10 tick 的无敌帧，所以 [damageInterval] 取 10 刚刚好
 * （再小也没用，伤害会被吞掉）。
 */
data class FluidContact(
    /** 着火秒数；0 = 不点火 */
    val igniteSeconds: Int = 0,
    /** 每次伤害的点数；0 = 不造成伤害 */
    val damage: Float = 0f,
    /** 每多少 tick 造成一次伤害（见类注释：不要小于 10） */
    val damageInterval: Int = 10,
    /** 伤害类型；[damage] > 0 时必须给，否则不会造成伤害 */
    val damageType: ResourceKey<DamageType>? = null,
    /** 销毁掉进来的普通物品的概率（0~1）；0 = 不销毁 */
    val destroyItems: Float = 0f,
    /** 像细雪一样冻结（累积冻结值 → 冻伤、减速、视野结霜） */
    val freezing: Boolean = false,
    /** 持续获得的状态效果 */
    val effects: List<ContactEffect> = emptyList(),
    /**
     * 每 tick 给掉进来的物品修复的耐久点数；0 = 不修复。
     *
     * 实际速率取配置项 `ember_repair_per_tick`（默认 20，岩浆是 10），
     * 这里只当开关用。上游的重铸表是"站在哪个方块里就每 tick 修多少"：
     * 火 2 / 灵魂火 5 / 岩浆与岩浆炼药锅 10，**不消耗任何燃料**。
     */
    val reforgePerTick: Int = 0,
) {
    /** 什么都不做 */
    val isHarmless: Boolean
        get() = igniteSeconds <= 0 && damage <= 0f && destroyItems <= 0f &&
            !freezing && effects.isEmpty() && reforgePerTick <= 0

    companion object {
        /** 无影响 */
        val NONE = FluidContact()
    }
}

/**
 * 本模组**自定义的伤害类型**。
 *
 * 只有需要专属死因文案的流体才值得新建：余烬流体不是岩浆，
 * 用 `minecraft:lava` 会写成"被岩浆烧死"。其余情况一律复用原版类型：
 *
 * | 流体 | 伤害类型 | 死因文案 |
 * | --- | --- | --- |
 * | 余烬流体 | `anvilcraft_fluid:ember`（本模组注册） | 本模组 lang |
 * | 浮霜流体 | `minecraft:freeze` | 原版"冻死"（就是细雪那一套） |
 * | 熔融诅咒金 | 无（只给虚弱） | — |
 * | 熔融铀 | 无（凋零效果自己结算伤害） | — |
 *
 * 余烬的伤害类型**同时挂进了原版 `#minecraft:is_fire` 标签**
 * （`src/main/resources/data/minecraft/tags/damage_type/is_fire.json`），
 * 这样它就能被防火附魔减免，物品侧的"火焰免疫"判定也照常生效——
 * 换句话说，它就是"另一种火"。对应的死因文案在 lang 的
 * `death.attack.anvilcraft_fluid.ember`。
 */
object AddonDamageTypes {
    /** 余烬灼烧 */
    val EMBER: ResourceKey<DamageType> =
        ResourceKey.create(Registries.DAMAGE_TYPE, AnvilCraftFluid.of("ember"))
}
