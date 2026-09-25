---
navigation:
  title: "熔融与冷却"
  icon: "anvilcraft_fluid:molten_iron_bucket"
items:
  - anvilcraft_fluid:molten_iron_bucket
  - minecraft:iron_block
---

# 熔融

在<ref item="anvilcraft:large_cauldron"/>里用**高温熔炼**把原料化成流体：

- 1 个金属块 或 1 个宝石块 → 1000mB 对应的熔融流体
- 9 个<ref item="minecraft:quartz"/> → 1000mB 熔融石英
- 9 个<ref item="minecraft:amethyst_shard"/> → 1000mB 熔融紫水晶

以铁与铅为例：

<recipe id="anvilcraft_fluid:super_heating/melting/iron"/>
<recipe id="anvilcraft_fluid:super_heating/melting/lead"/>

# 冷却

把熔融流体倒满炼药锅，让<ref item="minecraft:anvil"/>砸下去，熔液冷凝回**对应的方块物品**，锅随之被清空。

<recipe id="anvilcraft_fluid:solid_liquid/cooling/iron"/>
<recipe id="anvilcraft_fluid:solid_liquid/cooling/lead"/>

<tip>
冷却要求**满锅**（1000mB）：锅里不够一桶就不会触发。
</tip>