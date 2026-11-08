export type FileStatus = 'create' | 'update' | 'unchanged' | 'preserve_manual' | 'conflict';
export interface PlanFile {
  path: string;
  status: FileStatus;
  currentSha256?: string;
  candidateSha256?: string;
  currentPreview?: string;
  candidatePreview?: string;
}
export interface GeneratorPlan {
  planId: string;
  expiresAt: string;
  applicable: boolean;
  files: PlanFile[];
  conflicts: Array<string | { path: string; reason: string }>;
}
export interface GeneratorOperation {
  operationId: string;
  planId: string;
  status: string;
  createdAt?: string;
  files?: string[];
}
