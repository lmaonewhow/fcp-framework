/** IDs, versions and business codes never cross a JS Number conversion. */
export function uint64(value: unknown): string {
  if (typeof value !== 'string' || !/^(0|[1-9][0-9]{0,19})$/.test(value) || (value.length === 20 && value > '18446744073709551615')) {
    throw new Error('无效的记录编号或版本。');
  }
  return value;
}
/** SQL DATE is a business date, not a local/UTC instant. Keep it as supplied. */
export function businessDate(value: unknown): string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error('无效的业务日期。');
  const [year, month, day] = value.split('-').map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (year < 1000 || month < 1 || month > 12 || day < 1 || day > days[month - 1]) throw new Error('无效的业务日期。');
  return value;
}
/** Human names and stable codes are validated without silently rewriting them. */
export function requiredText(value: unknown, maximum: number): string {
  if (typeof value !== 'string' || !value.trim() || [...value].length > maximum) throw new Error(`请输入1至${maximum}个字符。`);
  return value;
}
