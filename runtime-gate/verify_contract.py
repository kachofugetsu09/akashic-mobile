#!/usr/bin/env python3
"""校验移动端锁文件、协议快照与指定核心源码是同一份可审计现实。"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from typing import cast


CANONICAL_RUNTIME_REPOSITORY = "https://github.com/kachofugetsu09/akashic-agent"
FULL_GIT_HASH_LENGTH = 40


def _read_object(path: Path) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} 必须是 JSON object")
    return cast(dict[str, object], value)


def _required_text(value: dict[str, object], key: str) -> str:
    item = value.get(key)
    if not isinstance(item, str) or not item:
        raise ValueError(f"{key} 必须是非空字符串")
    return item


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _git(core_root: Path, revision: str) -> str:
    return subprocess.run(
        ["git", "-C", str(core_root), "rev-parse", revision],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def _git_blob(core_root: Path, revision: str, source_path: str) -> bytes:
    return subprocess.run(
        ["git", "-C", str(core_root), "show", f"{revision}:{source_path}"],
        check=True,
        capture_output=True,
    ).stdout


def _require_tree(core_root: Path, revision: str, expected: str, label: str) -> None:
    """确认固定 revision 的 tree 未被替换。"""
    if _git(core_root, f"{revision}^{{tree}}") != expected:
        raise ValueError(f"{label} tree 与 runtime lock 不一致")


def _require_file_digest(path: Path, expected: str, label: str) -> None:
    """确认固定文件的内容摘要未被替换。"""
    if _sha256(path) != expected:
        raise ValueError(f"{label} digest 与 runtime lock 不一致")


def load_contract(mobile_root: Path) -> tuple[dict[str, object], list[str]]:
    """读取并校验 Gate 锁文件，返回锁内容和核心测试 node id。"""

    # 1. 校验锁文件及固定的权威仓库。
    gate_root = mobile_root / "runtime-gate"
    lock = _read_object(gate_root / "runtime-contract.lock.json")
    repository = _required_text(lock, "source_repository")
    if repository != CANONICAL_RUNTIME_REPOSITORY:
        raise ValueError(f"非权威核心仓库：{repository}")
    for key in (
        "capability_commit",
        "capability_tree",
        "provider_runtime_revision",
        "provider_runtime_tree",
    ):
        value = _required_text(lock, key)
        if len(value) != FULL_GIT_HASH_LENGTH or any(
            character not in "0123456789abcdef" for character in value
        ):
            raise ValueError(f"{key} 必须是完整的小写 Git SHA")
    if _required_text(lock, "relationship") != "tested_backward_compatible":
        raise ValueError("runtime relationship 必须是 tested_backward_compatible")

    # 2. 校验场景目录不可脱离锁文件漂移。
    catalog_path = mobile_root / _required_text(lock, "scenario_catalog")
    expected_catalog_hash = _required_text(lock, "scenario_catalog_sha256")
    if _sha256(catalog_path) != expected_catalog_hash:
        raise ValueError("runtime scenario catalog hash 不匹配")
    catalog = _read_object(catalog_path)
    if _required_text(catalog, "profile") != _required_text(lock, "profile"):
        raise ValueError("runtime lock 与 scenario catalog profile 不一致")
    scenarios = catalog.get("scenarios")
    if not isinstance(scenarios, list) or not scenarios:
        raise ValueError("runtime scenario catalog 必须包含场景")
    provider_tests: list[str] = []
    for index, scenario in enumerate(scenarios):
        if not isinstance(scenario, dict):
            raise ValueError(f"scenario[{index}] 必须是 object")
        typed_scenario = cast(dict[str, object], scenario)
        _required_text(typed_scenario, "id")
        provider_tests.append(_required_text(typed_scenario, "provider_test"))
        consumer_source = mobile_root / _required_text(typed_scenario, "consumer_source")
        consumer_test = _required_text(typed_scenario, "consumer_test")
        if not consumer_source.is_file():
            raise ValueError(f"移动端场景测试不存在：{consumer_source}")
        consumer_text = consumer_source.read_text(encoding="utf-8")
        markers = (f"fun `{consumer_test}`", f"fun {consumer_test}(")
        if not any(marker in consumer_text for marker in markers):
            raise ValueError(f"移动端场景测试标记不存在：{consumer_test}")

    # 3. 校验 schema 快照仍对应自己的历史来源，而不是当前核心全集。
    schema_source = _read_object(mobile_root / "protocol/source.json")
    if _required_text(schema_source, "source_repository") != CANONICAL_RUNTIME_REPOSITORY:
        raise ValueError("protocol source repository 不是权威核心仓库")
    snapshot_path = mobile_root / _required_text(schema_source, "snapshot_path")
    if _sha256(snapshot_path) != _required_text(schema_source, "sha256"):
        raise ValueError("protocol snapshot hash 不匹配")
    schema_commit = _required_text(schema_source, "source_commit")
    if len(schema_commit) != FULL_GIT_HASH_LENGTH or any(
        character not in "0123456789abcdef" for character in schema_commit
    ):
        raise ValueError("protocol source_commit 必须是完整的小写 Git SHA")
    if schema_commit != _required_text(lock, "capability_commit"):
        raise ValueError("协议能力提交与 runtime capability_commit 不一致")
    if _required_text(schema_source, "sha256") != _required_text(
        lock,
        "capability_schema_sha256",
    ):
        raise ValueError("协议快照 digest 与 runtime capability schema 不一致")
    return lock, provider_tests


def verify_core(mobile_root: Path, core_root: Path) -> list[str]:
    """确认指定核心 checkout 与锁一致，并返回已存在的测试 node id。"""

    lock, provider_tests = load_contract(mobile_root)

    # 1. checkout 和两份 tree 必须精确命中，禁止用浮动分支代替。
    runtime_revision = _required_text(lock, "provider_runtime_revision")
    capability_commit = _required_text(lock, "capability_commit")
    if _git(core_root, "HEAD") != runtime_revision:
        raise ValueError("核心 checkout revision 与 runtime lock 不一致")
    _require_tree(
        core_root,
        runtime_revision,
        _required_text(lock, "provider_runtime_tree"),
        "provider runtime",
    )
    _require_tree(
        core_root,
        capability_commit,
        _required_text(lock, "capability_tree"),
        "capability",
    )

    # 2. 分别校验 provider runtime 与能力锚点的 schema。
    schema_path = core_root / _required_text(lock, "source_schema_path")
    _require_file_digest(
        schema_path,
        _required_text(lock, "provider_runtime_schema_sha256"),
        "provider runtime schema",
    )

    schema_source = _read_object(mobile_root / "protocol/source.json")
    historical_schema = _git_blob(
        core_root,
        _required_text(schema_source, "source_commit"),
        _required_text(schema_source, "source_path"),
    )
    if hashlib.sha256(historical_schema).hexdigest() != _required_text(
        lock,
        "capability_schema_sha256",
    ):
        raise ValueError("协议快照与固定历史核心 commit 不一致")

    # 3. 每个场景必须指向当前 checkout 中真实存在的测试文件。
    for node_id in provider_tests:
        source_path = core_root / node_id.split("::", maxsplit=1)[0]
        if not source_path.is_file():
            raise ValueError(f"核心场景测试不存在：{node_id}")
    return provider_tests


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mobile-root", type=Path, required=True)
    parser.add_argument("--core-root", type=Path)
    parser.add_argument(
        "--print-field",
        choices=(
            "source_repository",
            "capability_commit",
            "provider_runtime_revision",
            "protocol_source_commit",
        ),
    )
    args = parser.parse_args()

    mobile_root = args.mobile_root.resolve()
    lock, _ = load_contract(mobile_root)
    if args.print_field is not None:
        if args.print_field == "protocol_source_commit":
            schema_source = _read_object(mobile_root / "protocol/source.json")
            print(_required_text(schema_source, "source_commit"))
        else:
            print(_required_text(lock, args.print_field))
        return 0
    if args.core_root is not None:
        verify_core(mobile_root, args.core_root.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
