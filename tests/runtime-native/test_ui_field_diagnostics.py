import unittest
from ui_field_diagnostics import field_accessible_names

class FieldDiagnosticTests(unittest.TestCase):
    def test_only_accessible_role_names_not_values_are_retained(self):
        self.assertEqual(field_accessible_names('- text: Message\n- textbox "Message Message": private-value\n    continuation-secret\n- textbox:\n- textbox "Message" [disabled]: another-secret'),[
            {'role':'textbox','name':'Message Message'}, {'role':'textbox','name':''}, {'role':'textbox','name':'Message'}])

if __name__=='__main__':unittest.main()
