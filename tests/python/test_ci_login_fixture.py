"""Keep synthetic CI accounts within the pinned native login DTO constraints."""
import pathlib
import sys
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import ci_ingestion

class CiLoginFixtureTests(unittest.TestCase):
    def test_generated_password_matches_native_5_6_2_length(self):
        # PasswordLoginBody at backend pin 8136a019 declares @Length(min=5,max=30).
        passwords = {ci_ingestion.fixture_password() for _ in range(64)}
        self.assertEqual(len(passwords), 64)
        self.assertTrue(all(5 <= len(value) <= 30 for value in passwords))
        self.assertTrue(all(len(value) == 24 for value in passwords))

    def test_fixture_uses_the_validated_generator(self):
        source = (ROOT / 'tools/ci_ingestion.py').read_text()
        self.assertIn('self.password = fixture_password()', source)
        self.assertNotIn('self.password = secrets.token_hex(16)', source)

if __name__ == '__main__':
    unittest.main()
