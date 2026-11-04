import base64
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'runtime/ingestion'))
import inputs
import worker


class WorkerTests(unittest.TestCase):
    def csv(self, entity, text, encoding='utf-8-sig'):
        return inputs.from_csv(entity, dict(classId='class-a', content=base64.b64encode(text.encode(encoding)).decode(), encoding=encoding))

    def test_utf8_bom_and_leading_zero(self):
        rows = self.csv('roster', '学号,姓名,班级\n001,示例学生甲,class-a\n')
        self.assertEqual(rows[0]['studentId'], '001')

    def test_gb18030(self):
        self.assertEqual(self.csv('roster', '学号,姓名,班级\n001,示例学生甲,class-a\n', 'gb18030')[0]['name'], '示例学生甲')

    def test_english_reordered_header(self):
        self.assertEqual(self.csv('roster', 'class_code,student_code,full_name\nclass-a,001,Demo\n')[0]['studentId'], '001')

    def test_duplicate_header(self):
        with self.assertRaises(ValueError): self.csv('roster', '学号,姓名,班级,姓名\n001,A,class-a,B\n')

    def test_unknown_header(self):
        with self.assertRaises(ValueError): self.csv('roster', 'id,name,class\n001,A,class-a\n')

    def test_wrong_columns(self):
        with self.assertRaises(ValueError): self.csv('roster', '学号,姓名,班级\n001,A\n')

    def test_empty_import(self):
        with self.assertRaises(ValueError): self.csv('roster', '学号,姓名,班级\n')

    def test_unsupported_encoding(self):
        with self.assertRaises(ValueError): inputs.from_csv('roster', dict(content='', encoding='utf-16', classId='class-a'))

    def test_invalid_base64(self):
        with self.assertRaises(ValueError): inputs.from_csv('roster', dict(content='%%%', encoding='utf-8-sig', classId='class-a'))

    def test_size_cap(self):
        with self.assertRaises(ValueError): self.csv('roster', 'x' * (inputs.MAX_BYTES + 1))

    def test_row_cap(self):
        with self.assertRaises(ValueError): self.csv('roster', '学号,姓名,班级\n' + '001,A,class-a\n' * 1001)

    def test_numeric_student_id_refused(self):
        with self.assertRaises(ValueError): inputs.map_record(dict(student_code=1, full_name='A', class_code='class-a'), inputs.FIELDS['roster'], 'roster')

    def test_business_normalizes_name(self):
        row = dict(studentId='001', name='  A  ', classId='class-a')
        self.assertEqual(worker.dispatch('roster', 'business', dict(records=[row], classId='class-a'))['records'][0]['name'], 'A')

    def test_business_duplicate_student(self):
        row = dict(studentId='001', name='A', classId='class-a')
        with self.assertRaises(ValueError): worker.dispatch('roster', 'business', dict(records=[row, row], classId='class-a'))

    def test_foreign_class(self):
        row = dict(studentId='001', name='A', classId='class-b')
        with self.assertRaises(ValueError): worker.dispatch('roster', 'business', dict(records=[row], classId='class-a'))

    def test_date_validation(self):
        row = dict(studentId='001', day='2026-02-30', status='present', classId='class-a')
        with self.assertRaises(ValueError): worker.dispatch('attendance', 'business', dict(records=[row], classId='class-a'))

    def test_attendance_unique_day(self):
        row = dict(studentId='001', day='2026-11-03', status='present', classId='class-a')
        with self.assertRaises(ValueError): worker.dispatch('attendance', 'business', dict(records=[row, row], classId='class-a'))

    def test_attendance_localized_status(self):
        row = self.csv('attendance', '学号,班级,日期,状态\n001,class-a,2026-11-03,迟到\n')[0]
        self.assertEqual(row['status'], 'late')

    def test_unknown_status(self):
        with self.assertRaises(ValueError): self.csv('attendance', '学号,班级,日期,状态\n001,class-a,2026-11-03,unknown\n')

    def test_leave_excluded_from_rate(self):
        rows = [dict(studentId=str(i), day='2026-11-03', status=status, classId='class-a') for i, status in enumerate(('present', 'leave'))]
        self.assertEqual(worker.dispatch('attendance', 'business', dict(records=rows, classId='class-a'))['summary']['attendanceRate'], 100)

    def test_duplicate_json_keys(self):
        with self.assertRaises(ValueError): inputs.strict_json('{"a":1,"a":2}')

    def test_nonfinite_json(self):
        with self.assertRaises(ValueError): inputs.strict_json('{"a":NaN}')

    def test_unrecognized_input_field(self):
        with self.assertRaises(ValueError): worker.dispatch('roster', 'business', dict(records=[], classId='class-a', scope='tenant-other'))

    def test_private_address_denied(self):
        import time
        with self.assertRaises(ValueError): inputs.fetch_json(dict(baseUrl='https://169.254.169.254'), '/', time.monotonic()+1)

    def test_plain_http_denied(self):
        import time
        with self.assertRaises(ValueError): inputs.fetch_json(dict(baseUrl='http://127.0.0.1:1'), '/', time.monotonic()+1)

    def test_credential_header_injection_denied(self):
        import time
        with self.assertRaises(ValueError): inputs.fetch_json(dict(baseUrl='http://127.0.0.1:1', allowLoopback=True, token='a\r\nX:b'), '/', time.monotonic()+1)


if __name__ == '__main__':
    unittest.main()
