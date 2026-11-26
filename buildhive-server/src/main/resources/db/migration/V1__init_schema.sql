-- BuildHive Database Schema Initialization
-- Generated for PostgreSQL 18 with Flyway

-- Enable pgcrypto extension for UUID generation if needed
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. Projects table: Stores registered Maven projects
CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- 2. Submissions table: Uploaded project archives and checksums
CREATE TABLE submissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    archive_path VARCHAR(1024) NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- 3. Pipelines table: End-to-end execution instances of a submission
CREATE TABLE pipelines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE
);

-- 4. Jobs table: Individual stages in a pipeline DAG
CREATE TABLE jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id UUID NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    job_type VARCHAR(50) NOT NULL,
    state VARCHAR(50) NOT NULL DEFAULT 'BLOCKED',
    priority INT NOT NULL DEFAULT 50,
    worker_id VARCHAR(100),
    claim_token VARCHAR(100),
    claim_time TIMESTAMP WITH TIME ZONE,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3,
    failure_classification VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE
);

-- 5. Job Dependencies table: Prerequisite relationships (parent -> child)
CREATE TABLE job_dependencies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    child_job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_job_dependencies UNIQUE (parent_job_id, child_job_id)
);

-- 6. Job Attempts table: Audit log for every worker execution or retry
CREATE TABLE job_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt_number INT NOT NULL,
    worker_id VARCHAR(100) NOT NULL,
    claim_token VARCHAR(100) NOT NULL,
    state VARCHAR(50) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    exit_code INT,
    failure_reason TEXT,
    failure_classification VARCHAR(50)
);

-- 7. Workers table: Registered worker instances and heartbeat tracking
CREATE TABLE workers (
    id VARCHAR(100) PRIMARY KEY,
    worker_name VARCHAR(255) NOT NULL,
    hostname VARCHAR(255) NOT NULL,
    last_heartbeat TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- 8. Artifacts table: Generated JAR files, reports, and logs
CREATE TABLE artifacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id UUID NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    artifact_type VARCHAR(50) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Performance & Queue Claiming Indexes
CREATE INDEX idx_jobs_state_priority ON jobs(state, priority DESC, created_at ASC);
CREATE INDEX idx_jobs_lease_expires_at ON jobs(lease_expires_at) WHERE state IN ('CLAIMED', 'RUNNING');
CREATE INDEX idx_jobs_pipeline_id ON jobs(pipeline_id);

CREATE INDEX idx_job_deps_child ON job_dependencies(child_job_id);
CREATE INDEX idx_job_deps_parent ON job_dependencies(parent_job_id);

CREATE INDEX idx_job_attempts_job_id ON job_attempts(job_id);
CREATE INDEX idx_artifacts_pipeline_id ON artifacts(pipeline_id);
CREATE INDEX idx_workers_heartbeat ON workers(last_heartbeat);
CREATE INDEX idx_pipelines_status ON pipelines(status);
