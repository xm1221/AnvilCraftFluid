package cn.xm1221.AnvilCraftFluid.block

import cn.xm1221.AnvilCraftFluid.AnvilCraftFluid
import cn.xm1221.AnvilCraftFluid.fluid.FluidSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.material.FlowingFluid

/**
 * 红石树脂胶体的液体方块：**会导电、而且记得"电是从哪边来的"的流体**。
 *
 * ## 行为
 *
 * 1. 被某一侧的红石激活后，它向**除那一侧以外**的五个方向输出 15 级
 *    （弱充能与强充能都给，红石元件、可充能方块都吃得到）；
 * 2. 它把"电是从哪边来的"记在状态里（[INPUT]），每次更新**先看那个方向还在不在供电**：
 *    在就继续工作，不在就重新找一个输入方向，找不到就**熄掉**；
 * 3. 所以放下、流到新的一格时都是**未激活**状态（没有输入就是暗的）；
 * 4. 源方块与流动方块是同一个类的不同档位，两者都会导电。
 *
 * ## 三个必须这么写的坑（都踩过）
 *
 * ### ① 排除来向，否则相邻两格互相供电锁死
 *
 * 如果两边互相输出，A 点亮 B、B 又点亮 A，撤掉拉杆也永远亮着。排除来向以后，
 * B 的回灌方向正好被它自己掐掉，信号流是一张**有向无环图**，撤掉源头就逐格熄灭。
 *
 * ### ② 查询输入时必须把自己"静音"，否则会被自己的输出"反射"回来
 *
 * 原版 `SignalGetter#getSignal(邻居, d)` 对**导电方块**邻居会补上
 * `getDirectSignalTo(邻居)`，而那份直接信号**包含我自己朝它输出的那 15**。
 * 结果：胶体只要紧挨石头/炼药锅这类导电方块，就会把"自己的输出"当成"从那边来的输入"，
 * 于是永远亮着（用户报的"始终被激活"），并且在多个导电邻居之间来回改 [INPUT]
 * → 无限递归 → `StackOverflowError: Exception while updating neighbours`（真崩过）。
 *
 * 解法与红石线一致（`RedStoneWireBlock` 的 `shouldSignal` 就是干这个的）：
 * 扫描期间把**自己这一格**的 [getSignal] / [getDirectSignal] 临时置 0。
 * 只静音"自己这一格"而不是整个类，是为了让**相邻的其它胶体照常说话**
 * （否则胶体之间就传不下去了）。
 *
 * ### ③ 逐格传导要走队列、同一格重入要挡掉，否则栈必爆
 *
 * 一片胶体几十格时，"改状态 → 通知邻居 → 邻居改状态 → 再通知"会层层压栈，深了就栈溢出。
 * 所以最外层那次更新建一个"待通知队列"，之后的每一格只**排队**，由最外层统一
 * `updateNeighborsAt` 逐轮推进（BFS），调用深度恒定。
 *
 * 光这样还不够：`Level#setBlock` 每次都会回调 [onPlace]，所以 `refresh → setBlock → onPlace
 * → refresh` 这条**同一格自递归**的路始终存在——只要状态还有可能来回翻转（比如被
 * "自己的输出反射回来"骗了），就是无限递归 + `StackOverflowError`（真崩过两次）。
 * 因此再加一道结构性保险 [Session.active]：同一格重入时**只排队重算、绝不继续往下压栈**。
 * 这样无论状态怎么抖，最多是多跑几轮循环，永远不会爆栈。
 *
 * ⚠️ 而"排队"必须**只在状态真的变了时**才做：无条件排队会变成"A 通知邻居 → B 重算 →
 * B 排队 → B 通知邻居 → A 重算 → A 排队"的无限打转，仅仅两格胶体挨着就能让每次方块更新
 * 白跑几千轮邻居通知（服务端 `Can't keep up! ... 81 ticks behind` +
 * `Too many chained neighbor updates`，真出现过）。只排队"变了的格子"，
 * 一轮传导的规模就等于"真正被点亮的格子数"，收敛也快。
 *
 * ## 亮不亮
 *
 * 未激活：一圈暗红边框，且用该格正常光照（[getLightEmission] 返回 0）。
 * 激活后：边框变亮红并**全亮**（渲染端走 FULL_BRIGHT），同时这一格真的**发光 15**
 * （[POWERED_LIGHT]，红石灯同款）——原版 `LevelChunk#setBlockState` 发现发光值变了
 * 会自动请求光照更新（`LightEngine.hasDifferentLightProperties` → `checkBlock`），
 * 不用自己调光照引擎。
 *
 * ## 方向参数的口径（很容易搞反，这里写清楚）
 *
 * 原版 `SignalGetter#getBestNeighborSignal` / `hasNeighborSignal` 都是
 * `getSignal(pos.relative(d), d)`，也就是 `getSignal` 的 `direction` 参数是
 * **"询问方看向被问方块的那个方向"**（红石线 `side != Direction.DOWN` 即"不向正上方供能"就是这个口径）。
 * 因此：我在方向 `d` 上找到输入 ⟹ 电源位于"我这边的 `d` 方向"（[INPUT] = `d`）；
 * 而位于 `INPUT` 那一侧的询问方，传进来的 `direction` 正好是 `INPUT.opposite()`——
 * **要掐掉的就是它**。
 *
 * ## 状态存在哪
 *
 * [POWERED] 与 [INPUT] 都存进方块状态：客户端靠 [POWERED] 切边框颜色
 * （`client/FluidOutlineRenderer`：激活亮红且全亮，未激活暗红且用该格正常光照）。
 *
 * ⚠️ 必须存状态、不能渲染时现算：属性变了才会给客户端发方块更新、那一格才会重画。
 *
 * ⚠️ 流体档位一变，原版 `LiquidBlock#createLegacyBlock` 会 `defaultBlockState()` 重建状态，
 * 把这两个属性一起冲掉——所以 [onPlace]（`LevelChunk#setBlockState` 每次都会调）
 * 与 [neighborChanged] 都要重算一次补回来。
 *
 * @param fluid 这种流体的 Flowing 本体（源/流动都由它派生）
 * @param properties 方块属性（由 FluidBuilder 给）
 * @param spec 流体定义，与其它液体方块保持同一套字段
 */
class RedstoneResinBlock(
    fluid: FlowingFluid,
    properties: Properties,
    spec: FluidSpec,
) : AddonLiquidBlock(fluid, properties, spec) {

    /** 在原版 `LEVEL` 之外再加两位：是否被点亮、电是从哪个方向来的 */
    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(POWERED)
        builder.add(INPUT)
    }

    /** 会主动给邻居供信号，原版靠这一位决定要不要来问 [getSignal] */
    override fun isSignalSource(state: BlockState): Boolean = true

    /** 弱充能：红石线、中继器这类"读邻居信号"的元件看这里 */
    override fun getSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int =
        if (muted(pos)) 0 else if (emitsTo(state, direction)) POWER else 0

    /** 强充能：让相邻的实体方块"被充能"，活塞这类由它决定 */
    override fun getDirectSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int =
        if (muted(pos)) 0 else if (emitsTo(state, direction)) POWER else 0

    /**
     * 激活时这一格自己发光（红石灯同款 15），未激活不发光。
     *
     * 原版发现发光值变了会自动更新光照，所以这里只要按状态给值就行。
     */
    override fun getLightEmission(state: BlockState, level: BlockGetter, pos: BlockPos): Int =
        if (state.getValue(POWERED)) POWERED_LIGHT else 0

    /**
     * 要不要往 `direction` 这一侧输出。
     *
     * `direction` 是询问方看过来时的方向，所以"询问方在供电的那一侧"等于
     * `direction == INPUT.opposite()`——那一边一律不输出（不回灌）。
     */
    private fun emitsTo(state: BlockState, direction: Direction): Boolean =
        state.getValue(POWERED) && direction != state.getValue(INPUT).opposite

    /** 胶体刚流到这一格 / 档位变了：重算一次 */
    override fun onPlace(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        oldState: BlockState,
        movedByPiston: Boolean,
    ) {
        super.onPlace(state, level, pos, oldState, movedByPiston)
        // 不传 state：apply 会自己读当前状态，见那里的注释
        refresh(level, pos)
    }

    /** 六个方向里任何一格变了（拉杆、红石线、别的胶体……）：重算一次 */
    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        neighborBlock: Block,
        neighborPos: BlockPos,
        movedByPiston: Boolean,
    ) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston)
        refresh(level, pos)
    }

    /**
     * 维持"输入方向仍然有效"这件事。
     *
     * - 已经有输入方向、而且那个方向还在供电 → 保持不动（不换来换去）；
     * - 否则重新找一个能供电的方向；找不到就熄灭。
     *
     * 正在传导（[PENDING_NOTIFY] 非空）时只改状态 + 排队，通知交给最外层，见类注释 ③。
     */
    private fun refresh(level: Level, pos: BlockPos) {
        // 客户端只收服务端发来的状态，不自己算（否则两边可能算出不同结果）
        if (level.isClientSide) return

        val running = SESSION.get()
        val outermost = running == null
        val session = running ?: Session().also { SESSION.set(it) }
        // ⚠️ 一律用 immutable 副本：原版在邻居更新链里**复用 MutableBlockPos**，
        // 直接存引用的话，这个对象的坐标会在后续代码里被改掉 → ThreadLocal 里记的东西全部错位
        // （静音失效 = 自己的输出又被当成输入，表现为"放下去时闪一下/偶尔自锁"；active 集合也会残留脏条目）。
        val key = pos.immutable()
        try {
            // 同一格重入（setBlock → onPlace → refresh）：只排队重算，绝不继续压栈，见类注释 ③
            if (!session.active.add(key)) {
                session.pending.add(key)
                return
            }
            try {
                val changed = apply(level, pos)

                // ⚠️ 只有**状态真的变了**才排队。
                // 无条件排队会变成"通知 → 邻居重算 → 邻居排队 → 再通知"的无限打转：
                // 仅仅两格胶体挨着就能让每次方块更新白跑几千轮邻居通知，服务端直接
                // "Can't keep up! Running 4059ms or 81 ticks behind" +
                // "Too many chained neighbor updates"（真出现过）。
                if (changed) session.pending.add(key)

                // 不是最外层、或者自己根本没变：到此为止
                if (!outermost || !changed) return

                // 最外层：逐轮通知，直到没有新的格子需要重算（每轮都是平级调用，深度恒定）
                var guard = 0
                while (session.pending.isNotEmpty() && guard++ < MAX_STEPS) {
                    val next = session.pending.first()
                    session.pending.remove(next)
                    level.updateNeighborsAt(next, this)
                }
            } finally {
                session.active.remove(key)
            }
        } finally {
            if (outermost) SESSION.set(null)
        }
    }

    /**
     * 按当前六个方向的信号，把 [POWERED] / [INPUT] 写成该有的样子。
     *
     * @return 状态是否真的被改了（没改 = 输出没变化 = 不需要通知任何邻居，见 [refresh]）
     *
     * 只带 `UPDATE_CLIENTS`：客户端要拿新状态重画边框（也顺带触发发光变化的光照更新），
     * 而**邻居通知由 [refresh] 的队列统一做**——这是不层层递归的关键。
     */
    private fun apply(level: Level, pos: BlockPos): Boolean {
        // ⚠️ 必须读**当前**状态，不能用调用方传进来的那份：
        //    onPlace / neighborChanged 收到的 state 可能是排队期间抓下的快照，
        //    拿旧快照当底再 setBlock 会把 LEVEL 等属性一起写回去 = 液面高度回跳一帧，
        //    表现就是"放下来时边框偶尔闪一下"（只在这条链的排队顺序不巧时才发生，所以是"偶尔"）。
        //    顺带挡住"这一格已经换成别的方块了"的情况。
        val live = level.getBlockState(pos)
        if (live.block !== this) return false

        val input = scan(level, pos, live)
        val newInput = input ?: live.getValue(INPUT)
        val powered = input != null
        val wasPowered = live.getValue(POWERED)
        if (wasPowered == powered && newInput == live.getValue(INPUT)) return false

        level.setBlock(pos, live.setValue(POWERED, powered).setValue(INPUT, newInput), Block.UPDATE_CLIENTS)
        return true
    }

    /**
     * 找一个能供电的方向：优先认已经记着的那个（只要它还活着），否则按固定顺序取第一个。
     *
     * 整个扫描期间把自己静音，避免"自己的输出经导电方块反射回来"被当成输入（类注释 ②）。
     */
    private fun scan(level: Level, pos: BlockPos, state: BlockState): Direction? {
        val previous = MUTED_POS.get()
        // 同上：必须存 immutable 副本。静音一旦失效，自己的输出会经相邻导电方块反射回来被当成输入
        MUTED_POS.set(pos.immutable())
        try {
            val current = state.getValue(INPUT)
            if (state.getValue(POWERED) && signalFrom(level, pos, current) > 0) return current
            return Direction.values().firstOrNull { signalFrom(level, pos, it) > 0 }
        } finally {
            MUTED_POS.set(previous)
        }
    }

    /**
     * 位于我这边的 `direction` 方向那一格，朝我这一侧发出来多少红石强度（0 = 没信号）。
     *
     * 口径照抄原版 `SignalGetter`：`getSignal(pos.relative(d), d)`。
     */
    private fun signalFrom(level: Level, pos: BlockPos, direction: Direction): Int =
        level.getSignal(pos.relative(direction), direction)

    /** 这一格此刻是不是在"扫描输入"中（是的话对外一律不输出） */
    private fun muted(pos: BlockPos): Boolean = pos == MUTED_POS.get()

    companion object {
        /** 是否被红石点亮。直接复用原版属性，省得为流体单独注册一套 */
        val POWERED: BooleanProperty = BlockStateProperties.POWERED

        /**
         * 电是从哪个方向来的（值 = 从这一格指向电源的方向）。
         *
         * [POWERED] 为 false 时这个值没有意义（保留上次的值，只用于记住来向）。
         */
        val INPUT: EnumProperty<Direction> = EnumProperty.create("input", Direction::class.java)

        /** 点亮时向外提供的红石强度（照红石块取满值 15） */
        const val POWER: Int = 15

        /** 点亮时自己发光多少（红石灯同款 15）；未激活 0，见 [getLightEmission] */
        const val POWERED_LIGHT: Int = 15

        /**
         * 正在扫描输入的那一格（**按线程**存，不能用静态字段：区块重建跑在渲染线程池上，
         * 而且服务端扫描与客户端渲染可能同时问信号）。
         */
        private val MUTED_POS: ThreadLocal<BlockPos?> = ThreadLocal()

        /**
         * 一次传导过程：最外层那次 [refresh] 建立、结束时清掉。**按线程**存，理由同上。
         *
         * - [pending]：改过状态、等着被通知邻居的格子，由最外层逐轮清空（BFS）；
         * - [active]：正在处理中的格子，用来挡"同一格重入"（见类注释 ③）。
         */
        private class Session {
            val pending: MutableSet<BlockPos> = mutableSetOf()
            val active: MutableSet<BlockPos> = mutableSetOf()
        }

        private val SESSION: ThreadLocal<Session?> = ThreadLocal()

        /** 一轮传导最多处理多少格，纯粹是防"两格互相改状态"这类意外，正常远达不到 */
        private const val MAX_STEPS: Int = 4096
    }
}
