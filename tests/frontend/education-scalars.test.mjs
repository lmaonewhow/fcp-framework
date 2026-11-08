import { describe, expect, it } from 'vitest';
import { uint64, businessDate, requiredText } from '@/fcp/education/scalars';
describe('education boundary values', () => {
  it.each(['0', '9007199254740993', '18446744073709551615'])('keeps uint64 %s exact', value => expect(uint64(value)).toBe(value));
  it.each([9007199254740992, '01', '-1', '1.0', '18446744073709551616', ' 1', '1e3'])('rejects malformed uint64 %s', value => expect(() => uint64(value)).toThrow());
  it.each(['2024-02-29', '2026-11-08'])('keeps DATE %s without timezone conversion', value => expect(businessDate(value)).toBe(value));
  it.each(['2026-02-29', '2026-13-01', '2026-00-10', '2026-04-31', '2026-11-08T00:00:00Z'])('rejects invalid DATE %s', value => expect(() => businessDate(value)).toThrow());
  it('preserves stable code leading zeros and user text', () => { expect(requiredText('001', 64)).toBe('001'); expect(requiredText(' 名称 ', 190)).toBe(' 名称 '); });
  it('counts Unicode code points rather than UTF16 units', () => expect(requiredText('𠀀'.repeat(190), 190)).toHaveLength(380));
  it.each(['', ' ', '𠀀'.repeat(191)])('rejects empty or excessive names', value => expect(() => requiredText(value, 190)).toThrow());
});
