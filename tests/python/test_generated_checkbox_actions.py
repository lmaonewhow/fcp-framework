"""Isolated locator/action regression, not native browser or business acceptance."""
import ast
from pathlib import Path
import sys
from types import ModuleType
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tests/generator')]
from business_runtime import checkbox_label, set_element_checkbox


class CheckboxControl:
    def __init__(self, checked=False, enabled=True):
        self.checked = checked
        self.enabled = enabled
        self.count = 1
        self.native_actions = []
    def is_checked(self): return self.checked
    def check(self, **kwargs): raise AssertionError('Must not check a hidden native input')
    def uncheck(self, **kwargs): raise AssertionError('Must not uncheck a hidden native input')
    def click(self, **kwargs): raise AssertionError('Must click the visible label, not hidden input')


class CheckboxLabel:
    def __init__(self, checked=False, enabled=True, visible=True, count=1, changes=True):
        self.control = CheckboxControl(checked, enabled)
        self.visible, self.count, self.changes = visible, count, changes
        self.clicks = 0
    def locator(self, selector):
        assert selector == 'input[type=checkbox]'
        return self.control
    def click(self, **kwargs):
        assert not kwargs, 'Do not disable pointer actionability with force or other overrides'
        assert self.visible and self.control.enabled
        self.clicks += 1
        if self.changes: self.control.checked = not self.control.checked


class Expectations:
    def __init__(self, target): self.target = target
    def to_have_count(self, count): assert self.target.count == count
    def to_be_visible(self): assert self.target.visible
    def to_be_enabled(self): assert self.target.enabled
    def to_be_checked(self): assert self.target.checked
    def not_to_be_checked(self): assert not self.target.checked


class GeneratedCheckboxActions(unittest.TestCase):
    def setUp(self):
        # Only these small action tests use locator doubles. Native acceptance stays real.
        api = ModuleType('playwright.sync_api');api.expect = Expectations
        playwright = ModuleType('playwright');playwright.sync_api = api
        self.modules = mock.patch.dict(sys.modules, {'playwright':playwright,'playwright.sync_api':api})
        self.modules.start();self.addCleanup(self.modules.stop)
    def test_form_and_table_selection_click_label_and_assert_actual_input(self):
        for initial, wanted, clicks in ((False,True,1),(True,False,1),(True,True,0),(False,False,0)):
            with self.subTest(initial=initial,wanted=wanted):
                label = CheckboxLabel(checked=initial)
                set_element_checkbox(label,wanted)
                self.assertEqual(label.control.checked,wanted)
                self.assertEqual(label.clicks,clicks)
    def test_multiple_select_single_select_clear_and_reselect(self):
        normal,disabled = CheckboxLabel(),CheckboxLabel()
        set_element_checkbox(normal,True);set_element_checkbox(disabled,True)
        self.assertTrue(normal.control.checked and disabled.control.checked)
        set_element_checkbox(disabled,False)
        self.assertTrue(normal.control.checked);self.assertFalse(disabled.control.checked)
        set_element_checkbox(normal,False)
        self.assertFalse(normal.control.checked or disabled.control.checked)
        set_element_checkbox(normal,True)
        self.assertTrue(normal.control.checked)
    def test_hidden_disabled_or_ambiguous_label_is_not_force_clicked(self):
        for label in (CheckboxLabel(visible=False),CheckboxLabel(enabled=False),CheckboxLabel(count=2)):
            with self.subTest(label=label), self.assertRaises(AssertionError):set_element_checkbox(label,True)
            self.assertEqual(label.clicks,0)
    def test_click_without_state_change_fails_checked_assertion(self):
        for initial,wanted in ((False,True),(True,False)):
            label=CheckboxLabel(checked=initial,changes=False)
            with self.assertRaises(AssertionError):set_element_checkbox(label,wanted)
            self.assertEqual(label.clicks,1)
    def test_label_selector_matches_exact_text_not_similar_options(self):
        scope=mock.Mock();result=checkbox_label(scope,'正常')
        scope.locator.assert_called_once_with('label.el-checkbox')
        pattern=scope.locator.return_value.filter.call_args.kwargs['has_text']
        self.assertTrue(pattern.fullmatch(' 正常\n'))
        self.assertFalse(pattern.fullmatch('非正常'))
        self.assertIs(result,scope.locator.return_value.filter.return_value)
    def test_all_generated_checkbox_interactions_use_visible_label_helper(self):
        source=(ROOT/'tests/generator/business_runtime.py').read_text()
        tree=ast.parse(source)
        forbidden=[]
        for node in ast.walk(tree):
            if isinstance(node,ast.Call) and isinstance(node.func,ast.Attribute):
                if node.func.attr in ('check','uncheck','set_checked','dispatch_event'):forbidden.append(node.func.attr)
                self.assertFalse(any(keyword.arg=='force' for keyword in node.keywords))
        self.assertEqual(forbidden,[])
        # Both edit selection and delete selection go through the same real label path.
        self.assertEqual(source.count("set_element_checkbox(row.locator('label.el-checkbox'),True)"),2)
        self.assertIn("expect(control).to_be_checked()",source)
        self.assertIn("expect(control).not_to_be_checked()",source)

if __name__=='__main__':unittest.main()
