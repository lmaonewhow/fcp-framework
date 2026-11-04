"""Attendance business rules; the Java host checks roster references before commit."""
from datetime import date
from roster import identifier

STATUSES = {'present', 'late', 'absent', 'leave'}


def normalize(records, class_id):
    identifier(class_id, 'classId')
    result, seen = [], set()
    counts = dict(present=0, late=0, absent=0, leave=0)
    for index, row in enumerate(records, 1):
        if not isinstance(row, dict) or set(row) != {'studentId', 'classId', 'day', 'status'}:
            raise ValueError(f'row {index}: attendance requires studentId, classId, day, status')
        student = identifier(row['studentId'], f'row {index} studentId')
        if row['classId'] != class_id:
            raise ValueError(f'row {index}: class differs from the authorized target')
        day = row['day']
        if not isinstance(day, str) or len(day) != 10 or date.fromisoformat(day).isoformat() != day:
            raise ValueError(f'row {index}: day must be an ISO calendar date')
        status = row['status']
        if not isinstance(status, str) or status not in STATUSES:
            raise ValueError(f'row {index}: unknown attendance status')
        key = student, day
        if key in seen:
            raise ValueError(f'row {index}: duplicate student/day in this batch')
        seen.add(key)
        counts[status] += 1
        result.append(dict(studentId=student, classId=class_id, day=day, status=status))
    result.sort(key=lambda r: (r['studentId'], r['day']))
    expected = len(result) - counts['leave']
    summary = dict(records=len(result), **counts,
                   attendanceRate=round(100 * (counts['present'] + counts['late']) / expected, 2) if expected else 0)
    return result, summary
