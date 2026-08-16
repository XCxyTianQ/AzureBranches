# -*- coding: utf-8 -*-
"""EXP6Plus smoke — Scoreboard 实体维度读集验收（v5 最终）。

关键环境事实（本 smoke 依赖）：
  - 无玩家在线时区块会卸载，僵尸对 @e 选择器不可见 → 每条链触发前
    必须新鲜召唤僵尸并在数秒内触发。
  - Folia 禁用了 Scoreboard.entityRemoved（ServerLevel 注释掉）→ 死实体
    分数不会自动清除 → 幽灵防护必须主动清除（EXP6Plus 实现）。
  - performCommand 无条件返回 true → 失败命令不打断链（vanilla 语义）。
  - RETRY 的 replay 不重放 impulse 的 say → PHASE-START 恒为 1。
  - RETRY + 幽灵防护的铁证 = C3 的 NO-ENTRY（只有回滚补偿的守卫清除
    才会删除死实体的分数条目）。
  - setblock 目标 (10,64,10) 与链同区块（chunk (0,0)，触发窗口内始终
    加载）——挂起机制只需一个注册了 future 的命令，实体维度 OCC 的
    挂起/校验/回滚链路与此目标的区域归属无关。

链 C1（存活）：set @e 5 → setblock 挂起 → 实体存活 → COMMIT →
  分数 5 保留 + setblock 写落地。
链 C2（死亡冲突）：set @e 7 → damage 致死 → setblock 挂起 →
  CHECK_ENTITY_READ_SET 检出实体死亡（含死亡动画窗口）→ RETRY →
  补偿对已死 holder 主动清除（幽灵分数防护，计入补偿失败统计）。
链 C3（幽灵检测）：死实体不应残留任何分数条目。
"""
import socket
import struct
import sys
import time

HOST, PORT, PASSWORD = "127.0.0.1", 25575, "test1234"
LOG = r"F:\AzureCore\AzureBranches\folia-server\build\libs\logs\latest.log"
Z = "@e[type=minecraft:zombie,limit=1]"
UUID = "00000001-0000-0002-0000-000300000004"

PASS = 0
FAIL = 0


def recv_packet(sock):
    header = b""
    while len(header) < 4:
        chunk = sock.recv(4 - len(header))
        if not chunk:
            raise ConnectionError("closed")
        header += chunk
    (length,) = struct.unpack("<i", header)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("closed mid-packet")
        data += chunk
    return data


def build_packet(packet_id, ptype, payload):
    body = struct.pack("<ii", packet_id, ptype) + payload + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


def cmd(command, wait=1.0):
    try:
        with socket.create_connection((HOST, PORT), timeout=10) as s:
            s.sendall(build_packet(1, 3, PASSWORD.encode()))
            recv_packet(s)
            s.sendall(build_packet(2, 2, command.encode("utf-8")))
            out = b""
            s.settimeout(wait)
            while True:
                try:
                    data = recv_packet(s)
                except (socket.timeout, ConnectionError):
                    break
                payload = data[8:]
                if payload.endswith(b"\x00\x00"):
                    payload = payload[:-2]
                out += payload
        text = out.decode("utf-8", "replace")
        print(f"> {command}")
        if text:
            print(f"< {text.strip()!r}")
        return text
    except OSError as e:
        print(f"[err] {command}: {e}")
        return ""


def set_impulse(x, z, command):
    cmd(
        f"setblock {x} 65 {z} minecraft:command_block[facing=north]"
        f'{{Command:"{command}"}}'
    )


def set_chain(x, z, command):
    cmd(
        f"setblock {x} 65 {z} minecraft:chain_command_block[facing=north]"
        f'{{Command:"{command}",auto:1b}}'
    )


def clear_area(x):
    for z in range(6, -4, -1):
        cmd(f"setblock {x} 65 {z} minecraft:air")


def fresh_zombie():
    cmd("kill @e[type=minecraft:zombie]")
    cmd(
        "summon minecraft:zombie 4 64 4 "
        "{NoAI:1b,PersistenceRequired:1b,UUID:[I;1,2,3,4]}"
    )
    cmd("scoreboard players set %s exp6p 0" % UUID)


def log_tail():
    try:
        with open(LOG, "rb") as f:
            data = f.read()
        text = data.decode("utf-8", errors="replace")
        idx = text.rfind("SMOKE-BEGIN")
        return text[idx:] if idx >= 0 else text[-6000:]
    except OSError:
        return ""


def log_assert(name, needles, min_count=1):
    global PASS, FAIL
    lines = log_tail()
    ok = True
    detail = []
    for n in needles:
        c = lines.count(n)
        detail.append(f"{n}={c}")
        if c < min_count:
            ok = False
    if ok:
        PASS += 1
        print(f"[PASS] {name} ({', '.join(detail)})")
    else:
        FAIL += 1
        print(f"[FAIL] {name} ({', '.join(detail)})")
        print("    log window:", lines[-500:].replace("\n", "\n    "))


def main():
    global PASS, FAIL
    import random
    marker = "SMOKE-BEGIN-%d" % random.randint(100000, 999999)
    cmd("say %s" % marker)
    time.sleep(1)
    # 清理历史残留 + 区块加载保障 + 夜间（防燃烧）
    for x in (4, 8, 12, 16):
        clear_area(x)
    cmd("forceload add 0 0")
    cmd("time set midnight")
    time.sleep(2)
    cmd("kill @e[type=minecraft:zombie]")
    time.sleep(1)
    cmd("scoreboard objectives add exp6p dummy")

    # ============ C1：实体存活 → COMMIT ============
    clear_area(4)
    set_impulse(4, 4, "say P1-PHASE-START")
    set_chain(4, 3, f"scoreboard players set {Z} exp6p 5")
    set_chain(4, 2, f"execute if score {UUID} exp6p matches 5 run say P1-SET-OK")
    set_chain(4, 1, "setblock 10 64 10 minecraft:stone")  # 注册 future → 挂起 + 校验
    set_chain(4, 0, f"execute if score {UUID} exp6p matches 5 run say P1-COMMIT-KEEP-5")
    set_chain(4, -1, "say P1-END")
    fresh_zombie()
    time.sleep(1)
    cmd("setblock 4 65 5 minecraft:redstone_block")
    time.sleep(12)
    cmd("setblock 4 65 5 minecraft:air")
    time.sleep(3)
    log_assert("C1 实体存活 COMMIT", ["P1-SET-OK", "P1-COMMIT-KEEP-5", "P1-END"])
    lines = log_tail()
    c1_start = lines.count("P1-PHASE-START")
    if c1_start == 1:
        PASS += 1
        print(f"[PASS] C1 无重试（PHASE-START={c1_start}）")
    else:
        FAIL += 1
        print(f"[FAIL] C1 意外重放（PHASE-START={c1_start}）")
    # setblock 写落地验证：stone→air 会记录消息；air→air 静默
    cmd("setblock 10 64 10 minecraft:air")
    log_assert("C1 setblock 写实际落地", ["Changed the block at 10, 64, 10"])
    clear_area(4)

    # ============ C2：实体死亡 → RETRY + 幽灵防护 ============
    clear_area(8)
    set_impulse(8, 4, "say P2-PHASE-START")
    set_chain(8, 3, f"scoreboard players set {Z} exp6p 7")
    set_chain(8, 2, f"execute as {Z} run damage @s 999999")  # 立即致死
    set_chain(8, 1, "setblock 10 64 10 minecraft:stone")    # 注册 future → 挂起 + 校验
    set_chain(8, 0, f"execute if score {UUID} exp6p matches 7 run say P2-SET-OK")
    set_chain(8, -1, "say P2-END")
    fresh_zombie()
    time.sleep(1)
    cmd("setblock 8 65 5 minecraft:redstone_block")
    time.sleep(15)  # 校验窗口 + RETRY + 重放
    cmd("setblock 8 65 5 minecraft:air")
    time.sleep(3)
    lines = log_tail()
    p2 = lines.count("P2-PHASE-START")
    if p2 == 1:
        PASS += 1
        print(f"[PASS] C2 impulse 仅一次（PHASE-START={p2}）")
    else:
        FAIL += 1
        print(f"[FAIL] C2 PHASE-START={p2}（预期 1）")
    # RETRY 铁证（间接）：守卫清除后死实体无条目 —— C3 验证
    cmd("setblock 10 64 10 minecraft:air")
    clear_area(8)

    # ============ C3：幽灵分数检测 ============
    # 幽灵防护 = 补偿对已死 holder 主动清除条目（Folia 禁用了 vanilla 的
    # entityRemoved）。C2 的 RETRY 补偿后，死实体不得残留任何分数条目。
    clear_area(12)
    set_impulse(12, 4, "say P3-START")
    set_chain(12, 3, f"execute unless score {UUID} exp6p matches -2147483648..2147483647 run say P3-NO-ENTRY")
    set_chain(12, 2, f"execute if score {UUID} exp6p matches 5 run say P3-GHOST-5")
    set_chain(12, 1, f"execute if score {UUID} exp6p matches 7 run say P3-GHOST-7")
    set_chain(12, 0, "say P3-END")
    time.sleep(1)
    cmd("setblock 12 65 5 minecraft:redstone_block")
    time.sleep(6)
    cmd("setblock 12 65 5 minecraft:air")
    time.sleep(3)
    lines = log_tail()
    ghost5 = "P3-GHOST-5" in lines
    ghost7 = "P3-GHOST-7" in lines
    noentry = "P3-NO-ENTRY" in lines
    if noentry and not ghost5 and not ghost7:
        PASS += 1
        print("[PASS] C3 无幽灵分数（RETRY 补偿清除了死实体条目）")
    else:
        FAIL += 1
        print(f"[FAIL] C3 幽灵检测 noentry={noentry} ghost5={ghost5} ghost7={ghost7}")
    clear_area(12)
    cmd("setblock 10 64 10 minecraft:air")
    cmd("kill @e[type=minecraft:zombie]")

    print(f"\n=== EXP6Plus smoke: {PASS} passed, {FAIL} failed ===")
    sys.exit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    main()
