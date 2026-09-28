---
navigation:
  title: "§b功能性流体"
  icon: "anvilcraft_fluid:frost_fluid_bucket"
items:
  - anvilcraft_fluid:frost_fluid_bucket
  - anvilcraft_fluid:ember_fluid_bucket
  - anvilcraft_fluid:cursed_gold_fluid_bucket
  - anvilcraft_fluid:redstone_resin_bucket
---

# 浮霜流体

洗掉物品上的**全部附魔**。

- 在<ref item="anvilcraft:large_cauldron"/>里，每洗掉 1 条附魔消耗 250mB 浮霜流体
- 洗下来的附魔变成**液态魔咒**：1 级 1mB、2 级 2mB、3 级 4mB、4 级 8mB、5 级 16mB……以此类推
- 被洗空的附魔书会变回普通的<ref item="minecraft:book"/>
- 泡在里面**就像待在细雪里**：冻结值会慢慢累积，冻伤了会掉血，视野也会结霜

# 余烬流体

让**余烬金属装备**自动修复，和泡在岩浆里一个道理，但快得多。

- 物品**泡在余烬流体里**就会一点点修好，比泡在岩浆里快得多——倒进炼药锅或者直接浇在地上都行
- 不消耗流体本身，是"接触即生效"的流体
- 只有余烬金属打造、带重铸性质的装备会响应

- 和**浮霜流体**流到一起会凝固：两边都是**源**方块时生成<ref item="minecraft:blackstone"/>；其余组合（包括源与流动相遇）生成<ref item="minecraft:stone"/>
- 炼制方法见[基于流体的配方](100_anvilcraft_fluid/012_alloy.md)

# 诅咒金流体

容纳了**诅咒**的熔融金，本身也是熔融金属的一员，可以冷却成<ref item="anvilcraft:cursed_gold_block"/>。

- 在大型炼药锅里，每洗掉 1 条诅咒消耗 500mB 熔融金，并产出等量的诅咒金流体
- 泡在里面会持续**虚弱**

# 红石-树脂混合物

混入了红石粉的树脂胶体，可以流动，被红石信号激活时强充能与之接触的可充能方块或激活红石元件，这种流体的流动会被大部分红石元件阻挡。