package cn.xm1221.AnvilCraftFluid.fluid

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import dev.dubhe.anvilcraft.util.FireReforgingUtil
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.EntityTypeTags
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.Level

/**
 * 把 [FluidContact] 真正施加到实体身上。**世界流体方块与炼药锅共用这一份**。
 *
 * 调用点只有两个（都是原版就有的钩子，不需要 mixin）：
 *
 * | 载体 | 入口 | 说明 |
 * | --- | --- | --- |
 * | 世界里的流体 | [cn.xm1221.AnvilCraftFluid.block.AddonLiquidBlock.entityInside] | 实体每 tick 在方块里被通知一次 |
 * | 炼药锅 | [cn.xm1221.AnvilCraftFluid.block.AddonCauldronBlock.entityInside] | 同上，但只认"真的泡在锅内液面里" |
 *
 * 上游的 `ExpFluidBlock`、`LavaCauldronBlock` 也是这么做的，所以这是跟随原版/上游的姿势。
 *
 * ⚠️ **只在服务端生效**：客户端也跑一遍会造成"本地看见着火/掉血"的错位。
 */
object FluidContactApplier {

    /** [spec] 的影响作用在 [pos] 处的 [entity] 上；不需要影响时立即返回 */
    fun apply(spec: FluidSpec, level: Level, pos: BlockPos, entity: Entity) {
        val contact = spec.contact
        if (contact.isHarmless) return
        if (level.isClientSide) return

        // 物品堆（只有掉落物才有）；下面几处都要用
        val itemStack = (entity as? ItemEntity)?.item

        // 1) 重铸修复（余烬）：上游工具自己会校验 FIRE_REFORGING 组件与耐久，条件不满足就什么都不做。
        //    返回值 = 这次到底修没修（未受损的装备会返回 false，所以下面还要单独看火焰免疫）。
        var reforged = false
        if (contact.reforgePerTick > 0 && itemStack != null) {
            reforged = FireReforgingUtil.repair(itemStack, AnvilCraftFluid.CONFIG.emberRepairPerTick, level, pos)
        }

        // "烧不掉"的目标：实体自身火焰免疫，或物品带火焰免疫组件（余烬金属装备、下界合金一类）。
        // ⚠️ 1.21 里"耐不耐火"是**数据组件**（`Item.Properties#fireResistant()` 落到它上面），
        //    物品上没有 `isFireResistant()` 方法。
        // ⚠️ 也必须自己判：原版把这条判定放在 `Entity#lavaHurt` 里而不是 `hurt()` 里，
        //    所以"火焰免疫"不会自动挡住我们打的伤害（上游的岩浆炼药锅同样是自己判的）。
        val fireProof = entity.fireImmune() || itemStack?.has(DataComponents.FIRE_RESISTANT) == true
        // 会点火的流体（余烬）就按岩浆的规矩来：火焰免疫的东西既不受伤也不被烧毁
        val burned = contact.igniteSeconds > 0 && fireProof

        // 2) 销毁普通物品（像岩浆那样）。默认不启用（`destroyItems = 0`）：
        //    普通物品是被下面的伤害打没的（物品只有 5 点"生命"，一跳 6 点就没了），
        //    这样和岩浆一样"先烧一下再消失"。想让它一进去就没，把 destroyItems 调成 1 即可。
        if (entity is ItemEntity && contact.destroyItems > 0f && !reforged && !fireProof &&
            level.random.nextFloat() < contact.destroyItems
        ) {
            entity.discard()
            return
        }

        // 3) 着火：取较大值，避免站在边缘时反复"续火-灭火"
        if (contact.igniteSeconds > 0) {
            entity.remainingFireTicks = maxOf(entity.remainingFireTicks, contact.igniteSeconds * 20)
        }

        // 4) 冻结：照细雪的做法——只把原版那个标记点起来，
        //    累积冻结值、冻伤、减速、视野结霜统统交给原版，由此"就像细雪"。
        if (contact.freezing && entity is LivingEntity &&
            !entity.type.`is`(EntityTypeTags.FREEZE_IMMUNE_ENTITY_TYPES)
        ) {
            entity.setIsInPowderSnow(true)
        }

        // 5) 周期性伤害：用"游戏刻取模"定时，就不必给每个实体存冷却状态。
        //    间隔不要小于 10（原版无敌帧），见 FluidContact 的类注释。
        val damageType = contact.damageType
        if (contact.damage > 0f && damageType != null && !burned &&
            level.gameTime % contact.damageInterval.coerceAtLeast(1).toLong() == 0L
        ) {
            entity.hurt(level.damageSources().source(damageType), contact.damage)
        }

        // 6) 状态效果：每 tick 用短时长刷新，一离开流体就会自然消退
        if (contact.effects.isNotEmpty() && entity is LivingEntity) {
            for (effect in contact.effects) {
                val mobEffect = BuiltInRegistries.MOB_EFFECT.get(effect.id) ?: continue
                entity.addEffect(
                    MobEffectInstance(
                        BuiltInRegistries.MOB_EFFECT.wrapAsHolder(mobEffect),
                        effect.durationTicks,
                        effect.amplifier,
                    ),
                )
            }
        }
    }
}
