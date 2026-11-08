"""Fusion wiring checks only; not native/generated education acceptance."""
from pathlib import Path
import sys
import unittest

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from ci_native import parse_args


class SmartSchoolFusionTests(unittest.TestCase):
    def test_same_host_composition_retains_all_read_and_apply_modules(self):
        args=parse_args(['--ingestion','--education','--roster','--generator'])
        self.assertTrue(args.education and args.roster and args.generator)
        self.assertFalse(args.generator_business)

    def test_generated_host_replacement_requires_a_separate_education_digest_plan(self):
        for extra in (['--education'],['--education','--roster'],['--roster']):
            with self.subTest(extra=extra),self.assertRaises(SystemExit):
                parse_args(['--ingestion','--generator','--generator-business',*extra])
        args=parse_args(['--ingestion','--generator','--generator-business'])
        self.assertTrue(args.generator_business)

    def test_branding_runs_after_all_business_modules_and_generator_frontend(self):
        source=(ROOT/'tools/ci_native.py').read_text()
        branding=source.index("NativeModule('branding'")
        for name in ('runtimeComposition','educationFoundation','educationRoster','generatorApply'):
            self.assertLess(source.index("NativeModule('"+name+"'"),branding)
        native=(ROOT/'tools/native.py').read_text()
        self.assertLess(native.index("if name == 'admin': apply_generator_frontend(project)"),native.index('apply_generator_branding(admin)'))

if __name__=='__main__':unittest.main()
