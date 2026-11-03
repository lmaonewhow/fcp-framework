"""Student-roster business rules. Host owns identities, transactions and persistence."""
import re
import unicodedata


def identifier(value, field):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}', value):
        raise ValueError(f'{field}: expected a 1..64 character identifier')
    return value


def normalize(records, class_id):
    identifier(class_id, 'classId')
    result, seen = [], set()
    for index, row in enumerate(records, 1):
        if not isinstance(row, dict) or set(row) != {'studentId', 'name', 'classId'}:
            raise ValueError(f'row {index}: roster requires studentId, name, classId')
        student = identifier(row['studentId'], f'row {index} studentId')
        if row['classId'] != class_id:
            raise ValueError(f'row {index}: class differs from the authorized target')
        name = row['name']
        if not isinstance(name, str):
            raise ValueError(f'row {index}: name must be text')
        name = unicodedata.normalize('NFC', name.strip())
        if not 1 <= len(name) <= 80 or any(unicodedata.category(c).startswith('C') for c in name):
            raise ValueError(f'row {index}: invalid name')
        if student in seen:
            raise ValueError(f'row {index}: duplicate studentId in this batch')
        seen.add(student)
        result.append(dict(studentId=student, name=name, classId=class_id))
    result.sort(key=lambda r: r['studentId'])
    return result, dict(students=len(result), classes=1 if result else 0)
