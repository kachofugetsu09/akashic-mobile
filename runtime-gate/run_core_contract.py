#!/usr/bin/env python3
"""在无网络、只读源码和临时状态目录中运行固定核心语义场景。"""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

from verify_contract import verify_core


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mobile-root", type=Path, required=True)
    parser.add_argument("--core-root", type=Path, required=True)
    parser.add_argument("--image", required=True)
    args = parser.parse_args()

    mobile_root = args.mobile_root.resolve()
    core_root = args.core_root.resolve()
    provider_tests = verify_core(mobile_root, core_root)
    before_tree = subprocess.run(
        ["git", "-C", str(core_root), "rev-parse", "HEAD^{tree}"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()

    # 1. 只把固定源码只读挂入容器；所有运行状态都进入匿名 tmpfs。
    command = [
        "docker",
        "run",
        "--rm",
        "--network",
        "none",
        "--read-only",
        "--cap-drop",
        "ALL",
        "--cap-add",
        "SETGID",
        "--cap-add",
        "SETUID",
        "--security-opt",
        "no-new-privileges",
        "--pids-limit",
        "512",
        "--tmpfs",
        "/sandbox:rw,exec,nosuid,nodev,size=256m",
        "--tmpfs",
        "/tmp:rw,exec,nosuid,nodev,size=512m",
        "--mount",
        f"type=bind,source={core_root},target=/app,readonly",
        "--env",
        "AKASHIC_HOST_UID=0",
        "--env",
        "AKASHIC_HOST_GID=0",
        "--env",
        "AKASHIC_WORKSPACE=/sandbox/workspace",
        "--env",
        "AKASHIC_PLUGIN_HOME=/sandbox/plugin-home",
        args.image,
        "pytest",
        "-q",
        "-p",
        "no:cacheprovider",
        *provider_tests,
    ]
    subprocess.run(command, check=True)

    # 2. read-only 是 Gate 不变量，结束后再核对 tree 防止实现回退。
    after_tree = subprocess.run(
        ["git", "-C", str(core_root), "rev-parse", "HEAD^{tree}"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if after_tree != before_tree:
        raise RuntimeError("核心源码在 Gate 执行期间发生变化")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
