# AnvilCraft-Fluid

[NeoForge 1.21.1](https://neoforged.net/) 的 [AnvilCraft](https://github.com/Anvil-Dev/AnvilCraft)（铁砧工艺）附属模组，为工艺系统扩展流体玩法。

> ⚠️ **开发中**：当前仓库处于前置准备阶段，尚未包含实际游戏内容。

## 计划内容

- 多种熔融流体（熔融宝石、熔融金属等）
- 流体配方（单流体转化、多流体混合）
- 流体反应（流体与流体、流体与世界的交互）
- 各流体的特殊行为（洗附魔、诅咒清洗、反向流动、装备修复加速、红石信号传导等）

详见仓库根目录的 `铁砧工艺_流体扩展_开发计划.md`。

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

## 许可

代码与资源均以 MIT 许可发布，见 `LICENSE` 与 `ASSETS_LICENSE`。
