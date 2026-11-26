// BuildHive Core Domain Types and Enums

export type JobState =
  | 'BLOCKED'
  | 'READY'
  | 'CLAIMED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'SKIPPED'
  | 'RETRY_WAIT'
  | 'CANCELLED';

export type JobType =
  | 'PROJECT_VALIDATION'
  | 'DEPENDENCY_RESOLUTION'
  | 'COMPILATION'
  | 'UNIT_TESTS'
  | 'STATIC_ANALYSIS'
  | 'PACKAGING'
  | 'REPORT_GENERATION';

export type PipelineStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED';

export type FailureClassification =
  | 'INFRASTRUCTURE_ERROR'
  | 'USER_CODE_ERROR';

export interface Project {
  id: string;
  name: string;
  repoUrl?: string;
  createdAt: string;
  updatedAt: string;
}

export interface Submission {
  id: string;
  project?: Project;
  archivePath: string;
  checksumSha256: string;
  createdAt: string;
}

export interface Pipeline {
  id: string;
  submissionId?: string;
  projectName?: string;
  status: PipelineStatus;
  startedAt?: string;
  completedAt?: string;
  cancellationRequested?: boolean;
  createdAt: string;
  jobs?: Job[];
}

export interface Job {
  id: string;
  pipelineId: string;
  jobType: JobType;
  state: JobState;
  priority: number;
  workerId?: string;
  claimToken?: string;
  claimTime?: string;
  leaseExpiresAt?: string;
  retryCount: number;
  maxRetries: number;
  failureClassification?: FailureClassification;
  completedAt?: string;
  createdAt: string;
}

export interface JobAttempt {
  id: string;
  jobId: string;
  attemptNumber: number;
  workerId: string;
  claimToken: string;
  state: JobState;
  exitCode?: number;
  failureReason?: string;
  failureClassification?: FailureClassification;
  startedAt: string;
  completedAt?: string;
}

export interface Worker {
  workerId: string;
  workerName: string;
  hostname: string;
  lastHeartbeat: string;
  status: 'ACTIVE' | 'UNRESPONSIVE' | 'DRAINING';
  registeredAt: string;
  currentJobId?: string | null;
  currentJobType?: string | null;
}

export interface DashboardMetrics {
  totalPipelines: number;
  runningJobs: number;
  readyJobs: number;
  onlineWorkers: number;
}

export interface Artifact {
  id: string;
  jobId?: string;
  pipelineId: string;
  artifactType: string;
  fileName: string;
  filePath: string;
  fileSizeBytes: number;
  sha256: string;
  createdAt: string;
  downloadUrl?: string;
}

export interface SubmissionResponse {
  submissionId: string;
  pipelineId: string;
  projectId: string;
  projectName: string;
  status: PipelineStatus;
  message: string;
}

export interface LogChunk {
  lines: string[];
}
