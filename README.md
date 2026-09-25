# AnvilCraft-Fluid

[NeoForge 1.21.1](https://neoforged.net/) 的 [AnvilCraft](https://github.com/Anvil-Dev/AnvilCraft)（铁砧工艺）附属模组，为它添加了一些流体、也许会有点用。

> ⚠️ **开发中**：配方与数值仍在调整，以游戏内表现和手册为准。

## 内容

### 20 种流体

| 分类 | 流体 |
| --- | --- |
| **熔融宝石**（6） | 红宝石、蓝宝石、黄玉、绿宝石、石英、紫水晶 |
| **熔融金属**（11） | 铁、金、铜、钨、皇家钢、铅、银、锡、锌、钛、铀 |
| **功能性流体**（3） | 浮霜流体、余烬流体、诅咒金流体 |

每种流体都配有**流体方块、炼药锅与桶**，可以倒进大型炼药锅、炼药锅与鱼缸。

### 配方

| 玩法 | 机制 | 例子 |
| --- | --- | --- |
| **熔融** | `anvilcraft:super_heating` | 铁块（或 9 铁锭）→ 1000mB 熔融铁 |
| **冷却** | `anvilcraft:solid_liquid` | 满锅熔融流体 + 铁砧落下 → 对应的方块 |
| **矿石转化** | 自研 `anvilcraft_fluid:multi_fluid_mixing` | 10mB 熔融红宝石 + 250mB 熔融金属 → 该金属的**深层**矿石；换熔融蓝宝石则是**浅层**矿石 |
| **合金** | 自研 `multi_fluid_mixing` | 1000mB 熔融铁 + 1000mB 任意熔融宝石（红/黄/蓝/绿）+ 1 钻石 → 熔融皇家钢；10mB 熔融黄玉 + 1000mB 熔融铁 → 磁铁块 |
| **功能流体** | 自研 `multi_fluid_mixing` | 1000mB 细雪 + 1 浮霜金属粒 → 浮霜流体；1000mB 原油 + 1000mB 熔岩 + 1 余烬金属粒 → 余烬流体 |
| **时移** | `anvilcraft:time_warp` | 下界合金锭 + 熔岩 → 远古残骸；下界合金碎片 + 熔融钨 → 远古残骸；满锅浮霜流体 + 皇家钢（块/锭/粒）→ 浮霜金属（块/锭/粒） |

所有配方都在**大型炼药锅**里由铁砧撞击触发，与上游工艺一致。



## 环境要求

| 依赖 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.241 |
| Kotlin for Forge | 5.9.0 |
| AnvilCraft | 1.6.0+snapshot.2339 |
| Anvil Lib | 2.0.0+snapshot.534 |
| JDK | 21 |
| Gradle | 8.14.5（wrapper） |


## 构建

```bash
./gradlew build          # 产物在 build/libs/
./gradlew runClient      # 启动开发客户端
./gradlew runData        # 生成数据（src/generated/resources）
```

## 开发提示

- 全部**文本目前只有中文**（`src/main/resources/assets/anvilcraft_fluid/lang/zh_cn.json`）；英文客户端会显示原始翻译键。
- 手册源文件在 `src/main/resources/assets/anvilcraft/ageratum/zh_cn/100_anvilcraft_fluid/`。
- 更长的设计记录见仓库根目录的 `铁砧工艺_流体扩展_开发计划.md`。

## 许可

代码与资源均以 MIT 许可发布，见 `LICENSE`
