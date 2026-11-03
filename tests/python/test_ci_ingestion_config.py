"""CI namespace and report semantics must match the genuine native acceptance guard."""
import pathlib
import re
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import ci_native


class CiIngestionConfigurationTests(unittest.TestCase):
    def test_random_namespace_matches_the_unchanged_native_guard(self):
        values = {ci_native.acceptance_redis_prefix() for _ in range(64)}
        self.assertEqual(len(values), 64)
        java = (ROOT / 'integration/ruoyi/src/test/java/org/dromara/test/FcpSessionCacheDeletionTest.java').read_text()
        pattern = re.search(r'prefix\.matches\("([^"]+)"\)', java).group(1)
        self.assertTrue(all(re.fullmatch(pattern, value) for value in values))

    def test_orchestration_uses_the_guarded_namespace(self):
        source = (ROOT / 'tools/ci_native.py').read_text()
        self.assertIn("'FCP_REDIS_PREFIX='+acceptance_redis_prefix()", source)
        self.assertNotIn("'FCP_REDIS_PREFIX=ci'+", source)

    def test_observed_build_and_assertion_failures_are_fail(self):
        for failure in (RuntimeError('compiler exited 1'), AssertionError('wrong result'), TimeoutError('build timeout')):
            with self.subTest(failure=type(failure).__name__):
                self.assertEqual(ci_native.failure_status(failure), 'FAIL')

    def test_missing_environment_is_blocked(self):
        self.assertEqual(ci_native.failure_status(ci_native.PrerequisiteError('missing runtime')), 'BLOCKED')


if __name__ == '__main__':
    unittest.main()
