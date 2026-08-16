# -*- coding: utf-8 -*-
"""EXP6 smoke test driver v8 — RCON 驱动 + 服务器日志断言。

关键经验（实测）：
  - 26.1.2 NBT：Health/Silent/Pos/NoAI 仍 PascalCase；数字后缀必需（10f）；
    装备为 equipment map（{mainhand:{id,count}}）；name= 选择器不可靠，
    用 type=minecraft:zombie + kill-all 保证唯一。
  - EXP walker 下 impulse（头块）的命令不生效——所有操作必须放 chain 块。
  - Folia 异步 RCON 只回传失败文本；say 广播只进服务器日志（UTF-16LE），
    全部断言走日志文件。
"""
import socket
import struct
import sys
import time

HOST, PORT, PASSWORD = "127.0.0.1", 25575, "test1234"
LOG = r"F:\AzureCore\AzureBranches\folia-server\build\libs\logs\latest.log"
Z = "@e[type=minecraft:zombie,limit=1]"

PASS = 0
FAIL = 0
marker = [0]


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


def set_impulse(x, z):
    # 头块命令不会被 OCC 重放执行——只放一个不参与断言的 say
    cmd(
        f"setblock {x} 65 {z} minecraft:command_block[facing=north]"
        '{Command:"say PROBE-IMPULSE"}'
    )


def set_chain(x, z, command):
    cmd(
        f"setblock {x} 65 {z} minecraft:chain_command_block[facing=north]"
        f'{{Command:"{command}",auto:1b}}'
    )


def log_tail():
    """读日志文件末尾 400 行（不依赖 marker 时序）。"""
    try:
        with open(LOG, "rb") as f:
            f.seek(0, 2)
            size = f.tell()
            f.seek(max(0, size - 40000))
            data = f.read()
        return data.decode("utf-8", errors="replace")
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
    with open(LOG, "rb") as f:
        f.seek(0, 2)
        marker[0] = f.tell()

    cmd("time set midnight")
    time.sleep(3)  # time set 是异步的，等它生效再召唤（否则僵尸会被阳光烧伤）
    cmd("kill @e[type=minecraft:zombie]")
    time.sleep(1)
    cmd("summon minecraft:zombie 4 64 4 {NoAI:1b,Invulnerable:1b,PersistenceRequired:1b}")
    time.sleep(1)

    # ============ Phase A (x=4)：功能矩阵，全部操作在 chain 块 ============
    set_impulse(4, 4)
    cmds_a = [
        "say PROBE-PHASE-START",
        f"data merge entity {Z} {{Health:10f}}",
        "execute if entity @e[type=minecraft:zombie,limit=1,nbt={Health:10f}] run say PROBE-A1-HEALTH-10",
        f"data modify entity {Z} Health set value 15",
        "execute if entity @e[type=minecraft:zombie,limit=1,nbt={Health:15f}] run say PROBE-A2-HEALTH-15",
        f"data modify entity {Z} Pos[0] set value 5.5",
        "execute if entity @e[type=minecraft:zombie,limit=1,nbt={Pos:[5.5d]}] run say PROBE-A3-POS",
        f"data merge entity {Z} {{equipment:{{mainhand:{{id:'minecraft:stone',count:1}}}}}}",
        "execute if entity @e[type=minecraft:zombie,limit=1,nbt={equipment:{mainhand:{id:'minecraft:stone'}}}] run say PROBE-A4-EQUIP",
        f"data get entity {Z} equipment.mainhand.id",
        f"data merge entity {Z} {{Silent:1b}}",
        "execute if entity @e[type=minecraft:zombie,limit=1,nbt={Silent:1b}] run say PROBE-A5-SILENT-1",
        f"data remove entity {Z} Silent",
        "execute unless entity @e[type=minecraft:zombie,limit=1,nbt={Silent:1b}] run say PROBE-A6-SILENT-GONE",
        "say PROBE-A7-END",
    ]
    z = 3
    for c in cmds_a:
        set_chain(4, z, c)
        z -= 1
    time.sleep(1)
    cmd("setblock 4 65 5 minecraft:redstone_block")
    time.sleep(6)
    cmd("setblock 4 65 5 minecraft:air")
    log_assert("Phase A 功能矩阵", [
        "PROBE-PHASE-START", "PROBE-A1-HEALTH-10", "PROBE-A2-HEALTH-15",
        "PROBE-A3-POS", "PROBE-A4-EQUIP", "PROBE-A5-SILENT-1",
        "PROBE-A6-SILENT-GONE", "PROBE-A7-END",
    ])

    # ============ Phase B (x=8)：OCC 读-写自冲突 → RETRY 循环 ============
    set_impulse(8, 4)
    cmds_b = [
        "say PROBE-PHASE-START",
        f"data merge entity {Z} {{Silent:1b}}",
        f"data get entity {Z} Health",                    # 读捕获（15）
        f"data modify entity {Z} Health set value 18",     # 写捕获（15→18）
        "setblock 300 64 4 minecraft:stone",              # 跨区（区域 (1,0)）→ 挂起 + OCC 校验
        "execute if entity @e[type=minecraft:zombie,limit=1,nbt={Health:18f}] run say PROBE-B1-HEALTH-18",
        "execute if entity @e[type=minecraft:zombie,limit=1,nbt={Silent:1b}] run say PROBE-B2-SILENT-1",
        "kill @e[type=minecraft:zombie,limit=1]",
    ]
    z = 3
    for c in cmds_b:
        set_chain(8, z, c)
        z -= 1
    time.sleep(1)
    cmd("setblock 8 65 5 minecraft:redstone_block")
    time.sleep(35)  # 挂起路径：远区任务 + 校验 + 最多 3 次重试（给足时间）
    cmd("setblock 8 65 5 minecraft:air")
    time.sleep(3)  # 等日志 flush
    lines = log_tail()
    log_assert("Phase B 写入生效", ["PROBE-B1-HEALTH-18", "PROBE-B2-SILENT-1"])
    b0 = lines.count("PROBE-PHASE-START")
    if b0 >= 2:
        PASS += 1
        print(f"[PASS] OCC 重试循环真实发生（Phase 重放 {b0} 轮）")
    else:
        FAIL += 1
        print(f"[FAIL] OCC 重试循环未发生（Phase 仅 {b0} 轮）")

    # 清理
    for z in range(4, -12, -1):
        cmd(f"setblock 4 65 {z} minecraft:air")
    for z in range(4, -5, -1):
        cmd(f"setblock 8 65 {z} minecraft:air")
    cmd("setblock 300 64 4 minecraft:air")
    cmd("kill @e[type=minecraft:zombie]")

    print(f"\n=== EXP6 smoke v8: {PASS} passed, {FAIL} failed ===")
    sys.exit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    main()
