#!/usr/bin/env python3
"""在全新隔离目录用真实 Fabric 验证独立服务器和单机；不启动 Windows Native。"""
import argparse
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
TIMEOUT_SECONDS = 300
POLL_SECONDS = 0.5
STOP_TIMEOUT_SECONDS = 30
DEFAULT_PORT = 25584
MAX_PORT = 65_535


def prepare(run, port):
    for side in ("client", "server"):
        (run / side).mkdir(parents=True)
    (run / "client/options.txt").write_text("guiScale:2\nlang:zh_cn\nrenderDistance:2\nmaxFps:60\npauseOnLostFocus:false\n")
    (run / "server/eula.txt").write_text("eula=true\n")
    (run / "server/server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n"
        "view-distance=2\nsimulation-distance=2\nmax-players=1\nspawn-protection=0\n"
        "level-type=minecraft:flat\ngenerate-structures=false\ndifficulty=peaceful\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},'
        '{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","structures":{}}\n'
    )


def wait_ready(server, logfile):
    deadline = time.monotonic() + TIMEOUT_SECONDS
    while "Done (" not in logfile.read_text(errors="replace"):
        if server.poll() is not None or time.monotonic() > deadline:
            raise RuntimeError(f"独立服务器启动失败：{logfile}")
        time.sleep(POLL_SECONDS)


def stop_process_group(process):
    if process is None:
        return
    # 即使启动器已退出，也回收同组的游戏和 Xvfb，避免超时后留下测试实例。
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=STOP_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=STOP_TIMEOUT_SECONDS)


def run_mode(command, mode, run_id, port):
    run = ROOT / "build/smoke" / f"{run_id}-{mode}"
    prepare(run, port)
    args = command + [f"-PsmokeRun={run.name}", f"-PsmokeMode={mode}", f"-PsmokePort={port}"]
    server = None
    client_process = None
    with (run / "server.log").open("w") as server_log, (run / "client.log").open("w") as client_log:
        try:
            if mode == "dedicated":
                # 端口已占用时失败，不连接或结束用户的现有实例。
                with socket.socket() as probe:
                    probe.bind(("127.0.0.1", port))
                server = subprocess.Popen(args + [":server:runSmoke"], cwd=ROOT,
                                          stdout=server_log, stderr=subprocess.STDOUT, start_new_session=True)
                wait_ready(server, run / "server.log")
            client = ["xvfb-run", "-a", "-s", "-screen 0 1280x720x24"] + args + [":client:runSmoke"]
            client_process = subprocess.Popen(client, cwd=ROOT, stdout=client_log, stderr=subprocess.STDOUT,
                                              start_new_session=True)
            client_process.wait(timeout=TIMEOUT_SECONDS)
            if client_process.returncode != 0:
                raise RuntimeError(f"客户端验收失败：{run / 'client.log'}")
            marker = run / "client/vanilla-ok.txt"
            if not marker.is_file():
                raise RuntimeError(f"未产生验收通过标记：{run}")
            if server is not None:
                server.wait(timeout=STOP_TIMEOUT_SECONDS)
                if server.returncode != 0:
                    raise RuntimeError(f"服务器未正常保存退出：{run}")
            print(marker.read_text(), flush=True)
        finally:
            # 只处理本次启动的进程组；正常路径由游戏断线事件保存并停服。
            stop_process_group(client_process)
            stop_process_group(server)
    return run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path,
                        help="运行 Gradle 的 JDK；Loom 1.19 与 Minecraft 26.2 须为 Java 25，省略时沿用当前环境")
    parser.add_argument("--mode", choices=["both", "dedicated", "integrated"], default="both")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT,
                        help="独服验收端口；已占用时使用其他端口，不处理现有服务器")
    args = parser.parse_args()
    if not 1 <= args.port <= MAX_PORT:
        parser.error("--port 必须位于 1..65535")
    # 不复用外部 Gradle 守护进程，让游戏子进程始终属于本次可回收的进程组。
    command = [str(ROOT / "gradlew"), "--offline", "--no-daemon", "--console=plain"]
    if args.java_home:
        command.append(f"-Dorg.gradle.java.home={args.java_home}")
    run_id = uuid.uuid4().hex[:12]
    modes = ("dedicated", "integrated") if args.mode == "both" else (args.mode,)
    for mode in modes:
        print(run_mode(command, mode, run_id, args.port), flush=True)


if __name__ == "__main__":
    main()
