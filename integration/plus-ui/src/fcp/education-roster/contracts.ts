import { uint64, businessDate } from '../education/scalars';
export const kinds = ['enrollments', 'memberships', 'assignments'] as const;
export type Kind = (typeof kinds)[number];
export const readOperations = [
  'schoolList',
  'enrollmentList',
  'enrollmentDetail',
  'membershipList',
  'membershipDetail',
  'assignmentList',
  'assignmentDetail'
] as const;
export type ReadOperation = (typeof readOperations)[number];
export type Operations = Record<ReadOperation, boolean>;
export interface Capabilities {
  schemaVersion: 'education-roster-v1';
  status: 'READY' | 'BLOCKED';
  readiness: {
    schoolAuthorization: string;
    transactionProtocol: string;
    nativeAcceptance: string;
    personDisplayPolicy: 'PENDING';
  };
  operations: Operations;
}
export interface School {
  id: string;
  name: string;
  rowVersion: string;
  readiness: 'READY' | 'EXECUTION_GRANT_MISSING';
  operations: Operations;
}
export interface Relation {
  id: string;
  schoolId: string;
  validFrom: string;
  validTo: string | null;
  createdAt: string;
  rowVersion: string;
  personDisplay: { status: 'PENDING'; reason: 'PERSON_DISPLAY_POLICY_PENDING' };
  admissionKey?: string;
  status?: string;
  enrollmentId?: string;
  classId?: string;
  className?: string;
  termId?: string;
  termCode?: string;
  termStatus?: string;
  roleCode?: string;
}
export interface Page<T> {
  items: T[];
  nextAfterId: string | null;
}
const invalid = () => new Error('名册响应格式不受支持，查询保持关闭。');
function object(input: unknown): Record<string, unknown> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw invalid();
  return input as Record<string, unknown>;
}
function text(input: unknown, maximum = 190): string {
  if (typeof input !== 'string' || !input || [...input].length > maximum) throw invalid();
  return input;
}
export function id(input: unknown): string {
  const value = uint64(input);
  if (value === '0') throw invalid();
  return value;
}
function enumeration<T extends string>(input: unknown, values: readonly T[]): T {
  if (!values.includes(input as T)) throw invalid();
  return input as T;
}
/** Keep microseconds as text. A JS Date would truncate them and localize UTC. */
export function utc(input: unknown): string {
  if (typeof input !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(input)) throw invalid();
  businessDate(input.slice(0, 10));
  if (input.slice(11, 13) > '23' || input.slice(14, 16) > '59' || input.slice(17, 19) > '59') throw invalid();
  if (input.slice(0, 19) === '9999-12-31T23:59:59' && (input.split('.')[1]?.replace('Z', '').padEnd(6, '0') || '000000') > '499999') throw invalid();
  return input;
}
function operations(input: unknown): Operations {
  const source = object(input);
  for (const key of readOperations) if (source[key] !== undefined && typeof source[key] !== 'boolean') throw invalid();
  return Object.fromEntries(readOperations.map((key) => [key, source[key] === true])) as Operations;
}
export function capabilities(input: unknown): Capabilities {
  const v = object(input),
    readiness = object(v.readiness),
    raw = object(v.operations);
  if (v.schemaVersion !== 'education-roster-v1' || readiness.personDisplayPolicy !== 'PENDING') throw invalid();
  // A new disclosure/write policy needs an explicit reviewed client contract, not a role-name guess.
  for (const key of ['personDisplay', 'enrollmentWrite', 'membershipWrite', 'assignmentWrite', 'personRename', 'termWrite'])
    if (raw[key] !== false) throw invalid();
  return {
    schemaVersion: 'education-roster-v1',
    status: enumeration(v.status, ['READY', 'BLOCKED']),
    readiness: {
      schoolAuthorization: text(readiness.schoolAuthorization),
      transactionProtocol: text(readiness.transactionProtocol),
      nativeAcceptance: text(readiness.nativeAcceptance),
      personDisplayPolicy: 'PENDING'
    },
    operations: operations(raw)
  };
}
export function school(input: unknown): School {
  const v = object(input);
  return {
    id: id(v.id),
    name: text(v.name),
    rowVersion: uint64(v.rowVersion),
    readiness: enumeration(v.readiness, ['READY', 'EXECUTION_GRANT_MISSING']),
    operations: operations(v.operations)
  };
}
export function relation(input: unknown, kind: Kind, schoolId: string): Relation {
  const v = object(input),
    policy = object(v.personDisplay);
  if (v.schoolId !== schoolId || policy.status !== 'PENDING' || policy.reason !== 'PERSON_DISPLAY_POLICY_PENDING') throw invalid();
  // Explicit projection only: never spread a raw row or render arbitrary server keys.
  const row: Relation = {
    id: id(v.id),
    schoolId: id(v.schoolId),
    validFrom: utc(v.validFrom),
    validTo: v.validTo === null ? null : utc(v.validTo),
    createdAt: utc(v.createdAt),
    rowVersion: uint64(v.rowVersion),
    personDisplay: {
      status: 'PENDING',
      reason: 'PERSON_DISPLAY_POLICY_PENDING'
    }
  };
  if (kind === 'enrollments')
    return {
      ...row,
      admissionKey: text(v.admissionKey, 64),
      status: enumeration(v.status, ['ACTIVE', 'TRANSFERRED', 'GRADUATED', 'SUSPENDED'])
    };
  const linked = {
    ...row,
    classId: id(v.classId),
    className: text(v.className, 128),
    termId: id(v.termId),
    termCode: text(v.termCode, 64),
    termStatus: enumeration(v.termStatus, ['DRAFT', 'OPEN', 'FROZEN', 'ARCHIVED'])
  };
  return kind === 'memberships'
    ? { ...linked, enrollmentId: id(v.enrollmentId) }
    : {
        ...linked,
        roleCode: enumeration(v.roleCode, ['TEACHER', 'SUBSTITUTE', 'HEAD'])
      };
}
export function compareId(left: string, right: string) {
  return left.length - right.length || left.localeCompare(right);
}
export function page<T extends { id: string }>(input: unknown, parse: (item: unknown) => T, afterId?: string | null): Page<T> {
  const v = object(input);
  if (!Array.isArray(v.items) || v.items.length > 100) throw invalid();
  const items = v.items.map(parse),
    nextAfterId = v.nextAfterId === null ? null : id(v.nextAfterId);
  let previous = afterId || '0';
  for (const item of items) {
    if (compareId(item.id, previous) <= 0) throw invalid();
    previous = item.id;
  }
  if (nextAfterId !== null && (!items.length || nextAfterId !== previous)) throw invalid();
  return { items, nextAfterId };
}
export const listOperation: Record<Kind, ReadOperation> = {
  enrollments: 'enrollmentList',
  memberships: 'membershipList',
  assignments: 'assignmentList'
};
export const detailOperation: Record<Kind, ReadOperation> = {
  enrollments: 'enrollmentDetail',
  memberships: 'membershipDetail',
  assignments: 'assignmentDetail'
};
