# EXP7 — Buffered Linear 存储格式 v4 规范（AzLinear v4）

> 状态：设计稿（随实现更新）
> 上游：Luminol / Arbor 的 `BufferedLinearRegionFile`（作者 MrHua269 / Little / xymb 系），
> 本衍生实现保持 GPLv3 署名；本规范定义 AzureBranches 的 **v4 布局**（策略 B：
> 读取兼容 0x02/0x03 与旧 linear 系列，写入新 v4）。

## 1. 设计目标（改进清单 → 布局决策）

| # | 改进 | v4 决策 |
|---|---|---|
| F1 | v3 的位置表无 CRC、加载时无偏移边界检查——损坏/截断文件可导致越界读或整桶错读 | v4 位置表每表项增加 **xxhash32（压缩桶内容哈希）** 与 **compressedLen**；加载时先做 `offset+len ∈ [数据区, 文件尾)` 边界校验 |
| F2 | v3 桶内容无整体校验（只有内部 chunk 段 xxhash32，且需解压后才可见） | v4 每桶压缩字节 xxhash32 —— 不解压即验 |
| F3 | v3 桶加载信任 `chunkSectionDataSize`——损坏长度 → 大分配/OOM | v4 读段长度时做上界校验（≤ 剩余缓冲且 ≤ MAX_CHUNK_SIZE），否则硬错拒载 |
| F4 | v3 无文件尾部标记——截断文件静默通过（数据区之后的截断不可见） | v4 数据区后追加 **footer**：superblock(8)+version(1)+表哈希(8)+reserved(4) |
| F5 | 格式无书面规范（仅代码注释） | 本规范 + 常量集中定义（`FormatConstants`） |
| F6 | 时间戳 | chunk 段时间戳保持 **long 毫秒**（上游已是；旧 linear 的 int 秒仅读取兼容） |
| F7 | 超大 chunk（>500MB 防护） | 与 vanilla 一致的 `MAX_CHUNK_SIZE=500MB` 防护（拒绝写入并抛 `RegionFileSizeException`，绝不 silent clear）；oversize API 在 IRegionFile 路径不可达（上游注释亦如此），以 Javadoc 说明 |
| F8 | 保存调度 | 沿用上游 flusher（checker 20ms + IO 线程池、写后 3s 超时刷盘）；Moonrise 保存路径经 `flushRegionsOnSave` 调 `flush()`（0006 锚点已接线）——提交前屏障已存在，无需新增 |

## 2. 文件布局

### 2.1 主文件（master，`r.<rx>.<rz>.mcc.linear`，策略 B 命名沿用）

```
[0,   14)    header        superblock(long,8) | version(byte,1=0x04) | compressionLevel(byte,1) | xxHash32Seed(int,4)
[14,  270)   位置表 v4     16 × Entry(16B)：bucketOffset(long,8) | compressedLen(int,4) | xxhash32(int,4)
                          全 0 = 该桶无数据；offset 必须落在数据区且 len 必填
[270, EOF)   bucket 数据区  bucketIdx 升序：rawLen(int,4) | compressedLen(int,4) | ZSTD(compressedLen) 字节
[EOF-21,EOF) footer        superblock(long,8) | version(byte,1) | positionTableXXHash64(long,8) | reserved(4)
```

- bucket 粒度：16 桶 × 64 chunk（BUCKET_SHIFT=6），与上游一致。
- 每桶 xxhash32 覆盖**整个桶块**：`rawLen(4)|compressedLen(4)|ZSTD` 全部字节（含长度头），seed=header 的 xxHash32Seed；写、读两侧一致，不解压即验（F2）。
- 桶内 chunk 段（解压后）：`secLen(int,4)` + chunkSection；chunkSection = `len(int,4)|timestamp(long,8)|xxhash32(int,4)|data`（上游 swap 段同构，写入主文件时保留）。

### 2.2 交换文件（swap，`r.<rx>.<rz>.mcc.linear.swp`）

沿用上游（超块 `0x1145141919810L`、版本 0x02、header＋1024×Sector(17B)、`DELETE_ON_CLOSE`、LZ4 段、60%/1MiB 自动压缩）——交换层不用格式版本演进，它只是 WAL 中转；v4 只改主文件布局。

### 2.3 兼容读取矩阵

| 主文件字节 | 版本 | 处理 |
|---|---|---|
| superblock=-0x200812250269L | 0x02 | 旧整文件 ZSTD → 迁移入 swap（`tryParseBlinearV2`）→ 下次 sync 升 v4 |
| 同上 | 0x03 | 旧桶式 → 按桶懒加载迁移 → 升 v4 |
| 同上 | **0x04** | 本格式 |
| superblock=0xc3ff13183cca9d9aL | 1/2/3 | xymb 祖宗线性 → 逐块迁移 → 升 v4 |
| 其他 | — | 硬错误拒绝（带超块十六进制与路径，绝不静默） |

## 3. 校验与失败语义

- 加载路径：超块 → 版本 → 位置表（**边界校验 + footer 表哈希**）→ 桶（压缩字节 xxhash32）→ 段（长度上界）→ chunkSection（内部 xxhash32）——任一层失败 → `IOException` 且**携带文件与偏移**，绝不返回部分数据。
- 写路径：`RegionFileSizeException` 超限；swap 写入失败不影响主文件；sync 失败置 `SYNCED=false` 重试并**日志必发**；恢复语义以 kill-test 验证。

## 4. 验证计划

1. **round-trip**：任意大小的 NBT（0B、1B、64KB、1MB、500MB 附近拒绝路径）写入→flush→新建实例读回→NBT 逐字节一致；覆盖 16 桶×64 chunk 全部槽位。
2. **损坏注入**：翻转 header/位置表/桶字节/footer —— 必须全部拒绝且为 `IOException`；截断文件同理。
3. **kill-test**：写→标记脏→flush 中途砍进程→重启→swap 恢复或主文件完整，世界可继续读写。
4. **迁移**：手工构造 0x02/0x03/线性 v1-v3 样本→加载→sync→产出 v4→验证数据一致。
5. **性能基线**：全桶写 + 全读延迟对比 MCA 与 v3；记录压缩比。
6. **多线程**：16 线程随机 chunk 读写（并发桶/段争用）→ 轮循校验。

## 5. 集成方式

- 类落位：`azurebranches-new`（`com.azurebranches.storage.*`），GPLv3 继承 + 上游署名头。
- 接入锚点：`ChunkSystemRegionFileStorage` / `MoonriseRegionFileIO` / `ChunkSystemChunkBuffer` / `MinecraftServer` 日志（transformSource 4 处 + 格式工厂 `EnumRegionFormat` 等价物）。
- 配置：`AzureBranchesConfig` 新增 `storage.region_format`（`mca`/`b_linear_v4`，默认 `mca`）+ 压缩级别/IO 线程/刷盘延迟。
- 依赖：lz4-java、zstd-jni、openhft-hashing（坐标与版本对齐上游）。
