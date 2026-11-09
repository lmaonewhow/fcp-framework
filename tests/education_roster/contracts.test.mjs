import { describe, expect, it } from 'vitest';
import { capabilities, relation, page, school as parseSchool, utc, id } from '@/fcp/education-roster/contracts';
import { caps, row, page as resultPage, school } from './fixtures.mjs';
describe('roster v1 explicit projection contract (synthetic unit data)', () => {
  it('keeps uint64 strings and UTC microseconds losslessly', () => {
    const actual = relation(row('18446744073709551615'), 'enrollments', '10');
    expect(actual.id).toBe('18446744073709551615'); expect(actual.rowVersion).toBe('9007199254740993');
    expect(actual.validFrom).toBe('2026-11-08T00:00:00.123456Z');
  });
  it.each([0, '0', '01', '18446744073709551616', '1e3', 9007199254740992])('rejects noncanonical identity %s', value => expect(() => id(value)).toThrow());
  it.each(['2026-02-29T00:00:00Z', '2026-11-08T24:00:00Z', '2026-11-08T00:00:00+08:00', '2026-11-08T00:00:00.1234567Z', '9999-12-31T23:59:59.5Z'])('rejects invalid or lossy UTC %s', value => expect(() => utc(value)).toThrow());
  it.each(['enrollments', 'memberships', 'assignments'])('strips all non-whitelisted %s fields, including nested private references', kind => {
    const actual = relation(row('1', '10', kind, { personId: 'SECRET', displayName: '秘密姓名', studentCode: '000SECRET', subjectCode: '秘密学科', changeReason: '私人原因', privateRef: { url: 'secret' }, personDisplay: { status: 'PENDING', reason: 'PERSON_DISPLAY_POLICY_PENDING', name: '秘密姓名' } }), kind, '10');
    expect(JSON.stringify(actual)).not.toMatch(/SECRET|秘密|私人|privateRef/);
  });
  it('rejects cross-school rows and unsupported disclosure policy', () => {
    expect(() => relation(row('1', '20'), 'enrollments', '10')).toThrow();
    expect(() => relation(row('1', '10', 'enrollments', { personDisplay: { status: 'READY', reason: 'anything' } }), 'enrollments', '10')).toThrow();
    expect(() => capabilities({ ...caps, operations: { ...caps.operations, personDisplay: true } })).toThrow();
    expect(() => capabilities({ ...caps, readiness: { ...caps.readiness, personDisplayPolicy: 'READY' } })).toThrow();
  });
  it('does not infer permissions from roles or unknown fields', () => {
    const result = parseSchool(school('10', { operations: { role: 'tenant-admin', enrollmentList: true } }));
    expect(result.operations.enrollmentList).toBe(true); expect(result.operations.assignmentList).toBe(false);
  });
  it('requires ordered incremental IDs and cursor equals last row, never loops or merges duplicate records', () => {
    const parse = value => relation(value, 'enrollments', '10');
    expect(page(resultPage([row('9007199254740993')], '9007199254740993'), parse, '9007199254740992').items[0].id).toBe('9007199254740993');
    for (const p of [resultPage([row('4'), row('4')]), resultPage([row('4'), row('3')]), resultPage([], '5'), resultPage([row('4')], '5')]) expect(() => page(p, parse)).toThrow();
    expect(() => page(resultPage([row('4')]), parse, '4')).toThrow();
  });
});
