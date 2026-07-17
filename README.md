# AzureBranches 🌊

> 一个力求实现命令方块语义完整性与异步链式执行的 Folia 下游试验项目

## 关于本项目

AzureBranches 并非对任何既有项目的复刻，而是一个独立的实验性 Folia 下游分支。我们的核心目标是：**在多线程 Regionized Ticking 模型下，尽可能恢复并保证命令方块的语义完整性**——让 `/setblock`、`/execute`、连锁方块、循环方块等核心机制在跨区域场景中正确工作。

当前版本已实现 **三级命令方块执行模式**（SAFE / ACCESS / EXP），其中 EXP v2 引入了 **Walking/Waiting 分离**、**Continuation MVCC 继承** 与 **Walker 前瞻批量调度** 等架构设计。详见各 Release 的 Patch Notes 及配套技术文档。

## 灵感来源与致谢

本项目的部分功能灵感来源于 [LuminolMC](https://github.com/LuminolMC/Luminol)（特别是其配置系统和实体优化思路），我们的实现对其进行了简化与重新设计，以适应 AzureBranches 自身的架构方向。

在此，谨向 **Luminol 开发团队 (EarthMe 等)** 致以最深的敬意——他们的开创性工作为 Folia 下游生态树立了标杆，我们从中受益良多。

## ⚠️ 注意事项

- **不推荐生产使用**：AzureBranches 目前处于实验阶段，服务端在区块读取与 IO 优化方面尚显不足。
- **性能追求者**：如果你对服务端性能有更高要求，建议转向更成熟的 [Arbor](https://github.com/LittleOvO233/Arbor)，其在区块优化、实体管理等维度均经过长期打磨。
- **大规模宣发**：当前阶段不建议对 AzureBranches 进行大规模宣发或推荐至生产环境。本项目更适合对 Folia 命令方块机制感兴趣的研究者、开发者进行学习与测试。

## 构建

```bash
./gradlew :azurebranches-server:build --no-configuration-cache
```

输出：`folia-server/build/libs/azurebranches-server-*.jar`

## 许可

MIT
