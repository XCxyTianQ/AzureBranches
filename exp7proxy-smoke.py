# -*- coding: utf-8 -*-
"""EXP7 proxy smoke v2 — RCON 驱动 EXP 链（@e 全程选择器版，26.1.2 兼容）。

26.1.2 事实：summon 的 UUID:[I;…] 不再生效（实体随机 UUID），实体记分板名
= 随机 UUID → 一切分数读写探针都用 @e[type=zombie,limit=1]；C3 幽灵检测改为
RCON 侧 scoreboard players list 必须为空（死实体条目被补偿清除）。
"""
import socket
import struct
import sys
import time

HOST, PORT, PASSWORD = "127.0.0.1", 25576, "test1234"
LOG = r"F:\AzureCore\AzureBranches\exp7-test\logs\latest.log"
Z = "@e[type=minecraft:zombie,limit=1]"
Y = 125

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
    cmd(f"setblock {x} {Y} {z} minecraft:command_block[facing=north]" f'{{Command:"{command}"}}')


def set_chain(x, z, command):
    cmd(f"setblock {x} {Y} {z} minecraft:chain_command_block[facing=north]" f'{{Command:"{command}",auto:1b}}')


def clear_area(x):
    for z in range(6, -4, -1):
        cmd(f"setblock {x} {Y} {z} minecraft:air")


def fresh_zombie():
    cmd("kill @e[type=minecraft:zombie]")
    cmd("summon minecraft:zombie 5 %d 4 {NoAI:1b,PersistenceRequired:1b}" % Y)
    time.sleep(1)


def log_tail():
    try:
        with open(LOG, "rb") as f:
            text = f.read().decode("utf-8", errors="replace")
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


def sb_entries():
    out = cmd("scoreboard players list", wait=1.0)
    if not out:
        return []
    return [ln.strip() for ln in out.splitlines() if ln.strip() and ':' not in ln.split(':')[0]]


def main():
    global PASS, FAIL
    import random
    marker = "SMOKE-BEGIN-%d" % random.randint(100000, 999999)
    cmd("say %s" % marker)
    time.sleep(1)
    for x in (4, 8, 12, 16):
        clear_area(x)
    cmd("forceload add 0 0")
    cmd("time set midnight")
    time.sleep(2)
    cmd("scoreboard objectives add exp6p dummy")
    cmd("scoreboard players reset @a exp6p")
    cmd("scoreboard players reset @e exp6p")
    time.sleep(1)

    # ============ C1：实体存活 → COMMIT（@e 全选择器） ============
    clear_area(4)
    set_impulse(4, 4, "say P1-PHASE-START")
    set_chain(4, 3, f"scoreboard players set {Z} exp6p 5")
    set_chain(4, 2, f"execute if score {Z} exp6p matches 5 run say P1-SET-OK")
    set_chain(4, 1, "setblock 10 64 10 minecraft:stone")
    set_chain(4, 0, f"execute if score {Z} exp6p matches 5 run say P1-COMMIT-KEEP-5")
    set_chain(4, -1, "say P1-END")
    fresh_zombie()
    time.sleep(1)
    cmd("setblock 4 %d 5 minecraft:redstone_block" % Y)
    time.sleep(12)
    cmd("setblock 4 %d 5 minecraft:air" % Y)
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
    cmd("setblock 10 64 10 minecraft:air")
    log_assert("C1 setblock 写实际落地", ["Changed the block at 10, 64, 10"])
    # RCON 侧复核：@e 分数 = 5（信息项：链内 P1-* 断言为权威，此处仅观察）
    out = cmd(f"scoreboard players get {Z} exp6p", wait=1.0)
    print(f"[INFO] C1 RCON 复核输出: {out!r}")
    clear_area(4)

    # ============ C2：实体死亡 → RETRY + 守卫清除 ============
    clear_area(8)
    set_impulse(8, 4, "say P2-PHASE-START")
    set_chain(8, 3, f"scoreboard players set {Z} exp6p 7")
    set_chain(8, 2, f"execute as {Z} run damage @s 999999")
    set_chain(8, 1, "setblock 10 64 10 minecraft:stone")
    set_chain(8, 0, f"execute if score {Z} exp6p matches 7 run say P2-SET-OK")
    set_chain(8, -1, "say P2-END")
    fresh_zombie()
    time.sleep(1)
    cmd("setblock 8 %d 5 minecraft:redstone_block" % Y)
    time.sleep(15)
    cmd("setblock 8 %d 5 minecraft:air" % Y)
    time.sleep(3)
    lines = log_tail()
    p2 = lines.count("P2-PHASE-START")
    if p2 == 1:
        PASS += 1
        print(f"[PASS] C2 impulse 仅一次（PHASE-START={p2}）")
    else:
        FAIL += 1
        print(f"[FAIL] C2 PHASE-START={p2}（预期 1）")
    cmd("setblock 10 64 10 minecraft:air")
    clear_area(8)

    # ============ C3：幽灵分数检测（RCON 侧空值断言） ============
    time.sleep(2)
    entries = sb_entries()
    # 只算 exp6p 相关？scoreboard players list 列出所有目标板持有者；重置后应为空
    if len(entries) == 0:
        PASS += 1
        print("[PASS] C3 无幽灵分数（scoreboard players list 为空）")
    else:
        FAIL += 1
        print(f"[FAIL] C3 幽灵检测：残留 {entries}")
    clear_area(12)
    cmd("setblock 10 64 10 minecraft:air")
    cmd("kill @e[type=minecraft:zombie]")

    print(f"\n=== EXP7 proxy smoke v2: {PASS} passed, {FAIL} failed ===")
    sys.exit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    main()
