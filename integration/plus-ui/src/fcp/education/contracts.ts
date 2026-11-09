/** Readiness is not a grant. Business operations need their own server authorization. */
export const operationLabels = {
  schoolCreate: '新建学校',
  orgList: '组织列表',
  orgDetail: '组织详情',
  orgCreate: '新建组织',
  orgRename: '修改组织名称',
  classList: '班级列表',
  classDetail: '班级详情',
  classCreate: '新建班级',
  classRename: '修改班级名称',
  termList: '学期查询',
  personRename: '修改人员显示名'
} as const;
export type Operation = keyof typeof operationLabels;
export interface FoundationCapabilities {
  schemaVersion: 'education-foundation-v1';
  status: 'BLOCKED' | 'READY';
  readiness: { schoolAuthorization: string; transactionProtocol: string; nativeAcceptance: string };
  operations: Record<Operation, boolean>;
  blockers: string[];
}
export function parseCapabilities(input: unknown): FoundationCapabilities {
  if (!input || typeof input !== 'object') throw new Error('能力响应格式不受支持，请刷新或联系管理员。');
  const value = input as Record<string, unknown>;
  const readiness = value.readiness as Record<string, unknown> | undefined;
  const operations = value.operations as Record<string, unknown> | undefined;
  if (
    value.schemaVersion !== 'education-foundation-v1' ||
    !['BLOCKED', 'READY'].includes(String(value.status)) ||
    !readiness ||
    !['schoolAuthorization', 'transactionProtocol', 'nativeAcceptance'].every((key) => typeof readiness[key] === 'string') ||
    !operations ||
    !Object.keys(operationLabels).every((key) => operations[key] === undefined || typeof operations[key] === 'boolean') ||
    !Array.isArray(value.blockers) ||
    !value.blockers.every((item) => typeof item === 'string')
  ) {
    throw new Error('能力响应格式不受支持，基础资料操作保持关闭。请刷新或联系管理员。');
  }
  const safeOperations = Object.fromEntries(Object.keys(operationLabels).map((key) => [key, operations[key] === true])) as Record<Operation, boolean>;
  return { ...(input as FoundationCapabilities), operations: safeOperations };
}
