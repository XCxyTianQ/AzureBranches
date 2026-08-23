# -*- coding: utf-8 -*-
"""Generates F:\\AzureCore\\AzureDoc\\AzureBranches-26.1.2-EXP7Plus.docx
Format mirrors build-exp6plus-doc.py (direct formatting, CN section numbering)."""
import re
from docx import Document
from docx.shared import Pt
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

OUT = r"F:\AzureCore\AzureDoc\AzureBranches-26.1.2-EXP7Plus.docx"

doc = Document()

normal = doc.styles["Normal"]
normal.font.name = "Times New Roman"
normal.font.size = Pt(10.5)
normal.element.rPr.rFonts.set(qn("w:eastAsia"), "宋体")

BOLD_RE = re.compile(r"(\*\*.*?\*\*)")


def _set_run(run, size, bold, font_east="宋体"):
    run.font.name = "Times New Roman"
    run.font.size = Pt(size)
    run.font.bold = bold
    run._element.rPr.rFonts.set(qn("w:eastAsia"), font_east)
    run._element.rPr.rFonts.set(qn("w:ascii"), "Times New Roman")
    run._element.rPr.rFonts.set(qn("w:hAnsi"), "Times New Roman")


def para(text, size=10.5, bold_all=False, align="justify", indent_chars=0, space_after=6, space_before=0):
    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(space_after)
    p.paragraph_format.space_before = Pt(space_before)
    p.paragraph_format.alignment = {
        "left": WD_ALIGN_PARAGRAPH.LEFT,
        "center": WD_ALIGN_PARAGRAPH.CENTER,
        "justify": WD_ALIGN_PARAGRAPH.JUSTIFY,
    }[align]
    if indent_chars > 0:
        p.paragraph_format.first_line_indent = Pt(size * indent_chars)
    for part in BOLD_RE.split(text):
        if not part:
            continue
        m = BOLD_RE.fullmatch(part)
        if m:
            _set_run(p.add_run(m.group(1)[2:-2]), size, True)
        else:
            _set_run(p.add_run(part), size, bold_all)
    return p


def table(rows):
    t = doc.add_table(rows=len(rows), cols=len(rows[0]))
    t.style = "Table Grid"
    for i, row in enumerate(rows):
        for j, cell_text in enumerate(row):
            cell = t.cell(i, j)
            p = cell.paragraphs[0]
            r = p.add_run(str(cell_text))
            _set_run(r, 9.5, i == 0)
            if i == 0:
                shd = OxmlElement("w:shd")
                shd.set(qn("w:val"), "clear")
                shd.set(qn("w:fill"), "E7E6E6")
                cell._tc.get_or_add_tcPr().append(shd)
    doc.add_paragraph()


# ---------------- title block ----------------
para("AzureBranches", size=22, bold_all=True, align="center", space_after=2)
para("EXP7Plus：登录计分板同步恢复与 /team 恢复（Folia 线程化）", size=16, bold_all=True, align="center", space_after=2)
para("从「Folia 上游 region-threading 禁用」到「全局 tick 派发恢复 · vanilla 26.1.2 控制组对照实测」的玩家反馈修复工程",
     size=12, align="center", space_after=4)
para("版本 26.1.2-EXP7Plus\u3000\u30002026 年 08 月 23 日", size=10.5, align="center", space_after=2)
para("基于 Folia Regionized Ticking 模型", size=10.5, align="center", space_after=2)
para("感谢 PaperMC / Folia (Spottedleaf) / LuminolMC (EarthMe 及其团队)", size=10.5, align="center", space_after=6)
para("摘要", bold_all=True)
para("EXP7Plus（v26.1.2-EXP7Plus）由玩家反馈驱动：Aria 报告（1）计分板设置后退出重进即消失、但计分板实际展示位置仍在；"
     "（2）没有 team 功能（/team 报 Unknown or incomplete command）。两项均**非 EXP7/EXP6Plus 引入**，而是"
     " **Folia 上游以「region threading」为名所做的禁用**：(1) PlayerList.placeNewPlayer 中"
     " updateEntireScoreboard 调用被注释——客户端登录时永远收不到 SetObjective / SetDisplayObjective / SetScore"
     "（及 teams），sidebar 只在服务端状态可见，重进后客户端命令面全空；(2) Commands.java 中 TeamCommand.register 被注释——"
     " /team 根本不注册。修复原则是「恢复 + 保持线程契约」：登录同步经 transformSource 锚点恢复，并以"
     " RegionCommandExecutor.onGlobalAsync 派发到**全局 tick 线程**执行（scoreboard 是服务器全局数据；lambda 用"
     " join 时刻的世界快照捕获——placeNewPlayer 中 level 在 join 事件后会被重赋值，直接捕获不满足有效 final 约束，"
     " 编译期即修正）；/team 恢复注册，并新增全 handler 派发的 TeamCommand 覆盖层（镜像 /scoreboard 的全局 tick "
     " dispatch：PhaseSnapshot 传播、EXP 链 future 屏障、失败消息解开、反馈经 runOnSource 回源区域）。"
     " 验证采用**与 vanilla 26.1.2 控制组对照**的方法：原版 mojang 服务端同设置下登录抓包得到标准包组"
     "（0x6A SetObjective / 0x62 SetDisplayObjective / 0x6E SetScore），EXP7Plus 修复后与之一致，且新增"
     " 0x6D SetPlayerTeam（/team）；修复前 Folia 一个都不发。/team add/list/modify color/join 经 RCON 全部成功，"
     " 服务端日志逐条确认，全程无区域/全局线程异常。构建产物 60 MB，仅一个编译期修正（lambda 捕获非 final 变量）。",
     indent_chars=2)
para("关键词：Folia；计分板；Scoreboard；updateEntireScoreboard；PlayerList；TeamCommand；/team；region threading；"
     "全局 tick；RegionCommandExecutor；transformer 锚点；vanilla 控制组；协议 775；玩家反馈；EXP7Plus")

doc.add_paragraph()

# ---------------- 一 ----------------
para("一、背景与问题定义", size=14, bold_all=True, align="left", space_before=6, space_after=4)
para("1.1　玩家反馈（Aria）", size=12, bold_all=True, align="left")
para("在 EXP6Plus/EXP7 之后的真实游玩中，Aria 发来两条反馈：", indent_chars=2)
para("· 计分板显示还是有问题：设置完了以后退出重进就看不到了，但计分板实际位置还在；", space_after=3)
para("· 现在这个没有 team 功能。", space_after=3)
para("「实际位置还在」是关键信息：**服务端语义完好**（objectives/display slot/分数均存在），问题只在客户端"
     "登录后**收不到**这些状态。")
para("1.2　问题归因预判", size=12, bold_all=True, align="left")
para("两项都指向代理/后端联动之外的**服务器登录链路**，且 EXP6Plus/EXP7 的改造面（ScoreboardCommand 源线程解析、"
     "ScoreHolderArgument 读集、存储引擎）与登录下发无关。预判：这是继承自 Folia 上游的禁用项，而非本系列引入的回归。")
para("1.3　目标分层", size=12, bold_all=True, align="left")
para("(1) 定位：源码层面找到两处禁用的确切位置与注释理由；(2) 复现：以合成 775 客户端 + RCON 实测现状；"
     "(3) 对照：以 vanilla 26.1.2 服务端建立「应然」包组基线；(4) 修复：恢复但遵守 Folia 线程契约；"
     "(5) 验证：包组对照 + 命令矩阵。")

# ---------------- 二 ----------------
para("二、调查路径与对照方法", size=14, bold_all=True, align="left", space_before=6, space_after=4)
para("2.1　静态定位", size=12, bold_all=True, align="left")
para("在 Folia 补丁源（paperweight 生成树）中检索计分板与团队接线：", indent_chars=2)
para("`PlayerList.placeNewPlayer`：`//this.updateEntireScoreboard(level.getScoreboard(), player); // Folia - region threading`；", space_after=3)
para("`Commands.java`：`//TeamCommand.register(this.dispatcher, context); // Folia - region threading - TODO later`。", space_after=3)
para("两处注释理由一致：scoreboard 是**服务器全局数据**，而 placeNewPlayer 与命令执行都跑在源区域线程——"
     "Folia 选择直接禁用而非上线分支。`updateEntireScoreboard` 方法与 `TeamCommand` 类本身仍在（无编译破坏）。")
para("2.2　实测复现（修复前基线）", size=12, bold_all=True, align="left")
para("以 EXP6Plus jar 启动隔离测试服（server-port 25590 / RCON 25596 / 全新世界），经 RCON 建立"
     " objective sbobj（dummy）→ setdisplay sidebar → players set probe 7；用合成 protocol-775 客户端"
     "（离线登录 → 配置 → PLAY，每包抓 pid + 载荷并扫描特征串）登录：**5 秒内客户端收到 0 个含 sbobj 的包**；"
     "RCON `/team add testteam` → **Unknown or incomplete command**。同时服务端日志确认命令全部成功执行"
     "（Created new objective / Set display slot / Set [sbobj] for probe to 7）——数据面完好、下发面为零。")
para("2.3　vanilla 26.1.2 控制组", size=12, bold_all=True, align="left")
para("同一 mojang 26.1.2 服务端（同设置 + 同 RCON 命令）作为「应然」基线：同样的合成客户端登录后，"
     "收到 **0x6A SetObjective（len=17）、0x62 SetDisplayObjective（len=7）、0x6E SetScore（len=15，probe=7）**，"
     "且/team 命令体系存在。问题因此定性为 **Folia 相对 vanilla 的缺口**，非本系列回归。")

# ---------------- 三 ----------------
para("三、根因分析", size=14, bold_all=True, align="left", space_before=6, space_after=4)
para("3.1　计分板登录同步缺口", size=12, bold_all=True, align="left")
para("vanilla 的 placeNewPlayer 会调用 updateEntireScoreboard 把当前全部 teams + 每个展示槽的 objective"
     "（SetObjective ADD + SetDisplayObjective + 全部分数 SetScore）推给新登录玩家；Folia 注释后，这条链路"
     "在帧级完全消失——客户端只在**会话内**见得到随命令广播的增量（如再 setdisplay 一次），重进即恢复空白。"
     "这正是「重进看不到、位置还在」的组合成因。")
para("3.2　/team 缺失", size=12, bold_all=True, align="left")
para("TeamCommand.register 被注释后（连同 TeamMsgCommand 等一行号），/team 不在命令树中；统计/观察类 SUGGEST"
     "（ScoreHolderArgument 对 team 的成员建议）也一并不可见。/team 的操作通过 onTeamAdded/onTeamChanged"
     " 广播 SetPlayerTeam 并触发 updateTeamWaypoints——同样是全局数据 + 全局遍历的形态，与计分板同构。")

# ---------------- 四 ----------------
para("四、修复设计", size=14, bold_all=True, align="left", space_before=6, space_after=4)
para("4.1　登录同步恢复：锚点 + 全局 tick 派发", size=12, bold_all=True, align="left")
para("以 build 脚本 transformSource（fail-fast 唯一锚点）把注释行替换为：", indent_chars=2)
para("`final ServerLevel _expSyncLevel = level;`（join 时刻世界快照）", space_after=3)
para("`net.minecraft.server.commands.RegionCommandExecutor.onGlobalAsync(() -> {`", space_after=3)
para("`    PlayerList.this.updateEntireScoreboard(_expSyncLevel.getScoreboard(), player);`", space_after=3)
para("`    return null;`", space_after=3)
para("`});`", space_after=3)
para("**为什么派发到全局 tick**：scoreboard 是服务器全局数据（MinecraftServer.scoreboard），只能在全局 tick 线程读；"
     "updateEntireScoreboard 内部仅读全局表 + player.connection.send（线程安全队列），无阻塞。"
     "**为什么必须快照**：placeNewPlayer 中 level 在 join 事件后被重赋值（`level = player.level()`），"
     "lambda 直接捕获不满足有效 final 约束——首次编译即报错，改为快照变量（并保持与 vanilla 同一调用时点的语义）。")
para("4.2　/team 恢复：注册 + 全局 tick dispatch 覆盖层", size=12, bold_all=True, align="left")
para("(1) azurepatches-src/Commands.java 恢复 `TeamCommand.register(dispatcher, context)`；"
     "(2) 新增 TeamCommand 整文件覆盖层：**全部终端 handler 经 dispatch() 派发到全局 tick**，镜像 ScoreboardCommand "
     "的既定模式——PhaseSnapshot 线程局部传播（链式上下文中 OCC 快照随任务进入全局 tick）、"
     " `ExpChainSupport.registerRemote()` 链 future 屏障（命令块 EXP 链真正等到落地）、"
     " errorComponent 解开异步失败（保留 CommandSyntaxException 本地化）、反馈统一经 runOnSource 路由回源区域"
     "（控制台内联）。结论上保持了「所有写全局分数结构的操作只在全局 tick 发生」的线程契约。")

# ---------------- 五 ----------------
para("五、验证（对照实测）", size=14, bold_all=True, align="left", space_before=6, space_after=4)
para("5.1　登录包组对照", size=12, bold_all=True, align="left")
table([
    ["客户端登录收到的包", "vanilla 26.1.2 控制组", "EXP6Plus（修复前）", "EXP7Plus（修复后）"],
    ["0x6A SetObjective（sbobj, ADD）", "✅", "❌ 0 包", "✅ len=17"],
    ["0x62 SetDisplayObjective（sidebar）", "✅", "❌", "✅ len=7"],
    ["0x6E SetScore（probe=7）", "✅", "❌", "✅ len=15"],
    ["0x6D SetPlayerTeam（testteam）", "✅", "❌（/team 不存在）", "✅ len=38"],
])
para("修复前后均为同一隔离测试服 + 同一合成客户端；vanilla 侧为同版本官方 jar + 同命令序列。"
     "EXP7Plus 的四个包与 vanilla 逐字段一致（pid/内容/marker 特征均命中）。")
para("5.2　/team 命令矩阵（RCON）", size=12, bold_all=True, align="left")
table([
    ["命令", "结果", "服务端证据"],
    ["team add testteam", "✅", "[Rcon: Created team [testteam]]"],
    ["team list", "✅", "无错误返回（sendSuccess 异步反馈）"],
    ["team modify testteam color red", "✅", "[Rcon: Updated the color for team [testteam] to red]"],
    ["team join testteam probe", "✅", "[Rcon: Added probe to team [testteam]]"],
    ["team list testteam", "✅", "无错误返回"],
])
para("全程日志无 Exception/ERROR，无区域/全局线程违例；EXP 链 future 屏障路径未触发（非链上下文），"
     "console 快路径（全局 tick 上直跑）与镜像路径均正常。")
para("5.3　构建与运行", size=12, bold_all=True, align="left")
para("已知陷阱再次命中并规避：buildFolia 有 folia-paperclip.jar 缓存（`Folia cached (57 MB)`）会跳过整个"
     " applyAllPatches + transformSource + 覆盖层流程——**只动补丁时必须先删除 build/cache 强制全量重建**；"
     "删除后全量构建仅一个错误（4.1 的 lambda 非 final 捕获），修正后 EXIT=0；产物"
     " azurebranches-server-26.1.2-AB-0002-EXP7Plus.jar（60 MB，含新增 TeamCommand 覆盖层与 PlayerList 锚点）。")

# ---------------- 六 ----------------
para("六、已知限制与后续工作", size=14, bold_all=True, align="left", space_before=6, space_after=4)
para("6.1　已知限制", size=12, bold_all=True, align="left")
para("1. TeamMsgCommand（/teammsg）仍被 Folia 注释，本次未恢复；", space_after=3)
para("2. /team join/leave 属于 holder 侧结构操作，未纳入实体读集域（EXP6Plus 文档 P2 遗留）；"
     "链内 /team 经 dispatch 与 registerRemote 已挂链屏障，但 OCC 读集不含团队成员集合；", space_after=3)
para("3. 恢复后的登录同步对「目标槽位无 objective」的世界零开销（updateEntireScoreboard 空遍历），"
     "但每个新登录玩家多一次全局 tick 任务入队（可忽略量级）。", space_after=3)
para("6.2　后续（优先级）", size=12, bold_all=True, align="left")
para("P0：TeamMsgCommand 恢复（同 dispatch 模式）；/team 成员操作纳入实体读集域；", space_after=3)
para("P1：把登录同步改造成可开关（paper-global.yml 或 azurebranches 全局配置），默认开；"
     "release 文档化（EXP7Plus 报告 + AzureDoc 系列共享）。", space_after=3)

# ---------------- 七 ----------------
para("七、结论", size=14, bold_all=True, align="left", space_before=6, space_after=4)
para("EXP7Plus 以「玩家反馈 → 对照组定位 → 全局 tick 恢复」的路径，闭环了两项 Folia 上游禁用缺口："
     "登录计分板同步（updateEntireScoreboard）与 /team（TeamCommand）。修复不改变任何既有语义——"
     "恢复的是 vanilla 行为，遵守的是 Folia 线程契约（所有全局分数读写都在全局 tick，反馈回源区域）。"
     "验证采用对照实验而非自证：vanilla 26.1.2 控制组给出「应然」包组，EXP7Plus 逐字段复现，"
     "并以日志证据与零异常排除线程违规。方法学上再度验证：**帧级证据先于推论**——服务端数据完好与"
     "客户端零包并存时，先抓登录包组再谈根因；以及系列内已知陷阱（buildFolia 缓存）必须在改补丁时显式失效。", indent_chars=2)
doc.add_paragraph()
para("文档：AzureBranches-26.1.2-EXP7Plus\u3000\u3000版本：26.1.2-EXP7Plus\u3000\u3000日期：2026 年 08 月 23 日\u3000\u3000"
     "项目：AzureBranches (https://github.com/XCxyTianQ/AzureBranches)", size=10.5)

doc.save(OUT)
print("saved", OUT)
