import subprocess
import tempfile
import unittest
from pathlib import Path

from verify_contract import _require_file_digest, _require_tree


def _git(repository: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


class VerifyContractTest(unittest.TestCase):
    def test_runtime_tree_tampering_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            _git(repository, "init", "--quiet")
            _git(repository, "config", "user.name", "Runtime Gate Test")
            _git(repository, "config", "user.email", "runtime-gate@example.invalid")

            (repository / "runtime").write_text("runtime", encoding="utf-8")
            _git(repository, "add", "runtime")
            _git(repository, "commit", "--quiet", "-m", "runtime")
            runtime_revision = _git(repository, "rev-parse", "HEAD")

            with self.assertRaisesRegex(ValueError, "provider runtime tree"):
                _require_tree(repository, runtime_revision, "0" * 40, "provider runtime")

    def test_runtime_schema_digest_tampering_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            schema = Path(directory) / "mobile-realtime-v1.json"
            schema.write_text("{}", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "provider runtime schema digest"):
                _require_file_digest(schema, "0" * 64, "provider runtime schema")


if __name__ == "__main__":
    unittest.main()
