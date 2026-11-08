"""Fail-closed source/build wiring, not real native acceptance."""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from ci_native import parse_args, wait_fused_generator_login_window

class EducationCodegenWiringTests(unittest.TestCase):
    def test_full_combination_and_each_missing_prerequisite(self):
        flags=['--ingestion','--education','--roster','--generator']
        self.assertTrue(parse_args([*flags,'--education-generator']).education_generator)
        for flag in flags:
            with self.subTest(flag=flag), self.assertRaises(SystemExit):
                parse_args([x for x in flags if x!=flag]+['--education-generator'])

    def test_source_check_precedes_db_and_build_is_bound_afterward(self):
        source=(ROOT/'tools/ci_native.py').read_text()
        self.assertLess(source.index("report['educationGenerationInputs']=validate_prebuild_report(ROOT)"),source.index("sql(f'CREATE DATABASE"))
        self.assertGreater(source.index("report['educationGenerationBuild']="),source.index('command(build)'))
        self.assertIn("'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest()",source)
        self.assertIn("if report['generatorApply'].get('status')!='PASS'",source)
        self.assertLess(source.index("NativeModule('generatorApply'"),source.index("NativeModule('educationGenerator'"))
        self.assertLess(source.index("NativeModule('educationGenerator'"),source.index("NativeModule('branding'"))

    def test_full_native_window_without_changing_limiter(self):
        with patch('ci_native.time.sleep') as sleep, patch('ci_native.time.monotonic',side_effect=[5,65]):
            self.assertEqual(wait_fused_generator_login_window(),{'stage':'generator-apply','waitSeconds':60,'nativeRateLimitChanged':False})
            sleep.assert_called_once_with(60)
        with patch('ci_native.time.sleep'),patch('ci_native.time.monotonic',side_effect=[5,64.9]):
            with self.assertRaises(RuntimeError):wait_fused_generator_login_window()

    def test_workflow_builds_generated_clone_not_original_checkout(self):
        source=(ROOT/'.github/workflows/native-generated-school.yml').read_text()
        self.assertIn('tools/ci_education_codegen.py --clone-dir',source)
        self.assertIn('tools/ci_education_codegen.py --native-root "$PWD"',source)
        self.assertIn('working-directory: ${{ steps.codegen.outputs.generated_root }}',source)
        self.assertNotIn('--generator-business',source)
        self.assertNotIn('$GITHUB_WORKSPACE/work/ci-native',source)
        self.assertIn('work/education-generated-source/reports/education-codegen-native/',source)

    def test_generated_controllers_are_in_source_evidence(self):
        for script in ('verify_education_foundation.py','verify_education_roster.py'):
            self.assertIn('integration/ruoyi/src/main/java/org/dromara/fcp/education/**/*.java',(ROOT/'tools'/script).read_text())

if __name__=='__main__':unittest.main()
