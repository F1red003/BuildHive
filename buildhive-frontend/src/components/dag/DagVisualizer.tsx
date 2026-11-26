import React from 'react';
import {
  Clock,
  Code2,
  Cpu,
  FileCheck,
  FileCode,
  FolderGit2,
  Package,
  ShieldCheck,
  TestTube2,
} from 'lucide-react';
import { StatusBadge } from '../common/StatusBadge';
import type { Job, JobType } from '../../types';
import { cn, formatDuration } from '../../utils';

interface DagVisualizerProps {
  jobs: Job[];
  selectedJobId: string | null;
  onSelectJob: (job: Job) => void;
}

interface StageMeta {
  type: JobType;
  title: string;
  icon: React.ComponentType<{ className?: string }>;
  description: string;
}

const STAGE_META: Record<JobType, StageMeta> = {
  PROJECT_VALIDATION: {
    type: 'PROJECT_VALIDATION',
    title: 'Project Validation',
    icon: FileCheck,
    description: 'Archive integrity, pom.xml & coordinates',
  },
  DEPENDENCY_RESOLUTION: {
    type: 'DEPENDENCY_RESOLUTION',
    title: 'Dependency Resolution',
    icon: FolderGit2,
    description: 'mvn dependency:go-offline',
  },
  COMPILATION: {
    type: 'COMPILATION',
    title: 'Compilation',
    icon: Code2,
    description: 'mvn compile -B -e',
  },
  UNIT_TESTS: {
    type: 'UNIT_TESTS',
    title: 'Unit Tests',
    icon: TestTube2,
    description: 'mvn test & surefire report parse',
  },
  STATIC_ANALYSIS: {
    type: 'STATIC_ANALYSIS',
    title: 'Static Analysis',
    icon: ShieldCheck,
    description: 'Java source quality checks',
  },
  PACKAGING: {
    type: 'PACKAGING',
    title: 'Packaging',
    icon: Package,
    description: 'mvn package -DskipTests & artifact hash',
  },
  REPORT_GENERATION: {
    type: 'REPORT_GENERATION',
    title: 'Report Generation',
    icon: FileCode,
    description: 'Aggregate build summary & artifacts',
  },
};

export const DagVisualizer: React.FC<DagVisualizerProps> = ({
  jobs,
  selectedJobId,
  onSelectJob,
}) => {
  const getJobByType = (type: JobType): Job | undefined => {
    return jobs.find((j) => j.jobType === type);
  };

  const renderNode = (type: JobType) => {
    const job = getJobByType(type);
    const meta = STAGE_META[type];
    const Icon = meta.icon;
    const isSelected = job && job.id === selectedJobId;
    const state = job?.state || 'BLOCKED';
    const isRunning = state === 'RUNNING';

    return (
      <div
        key={type}
        onClick={() => job && onSelectJob(job)}
        className={cn(
          'relative w-64 rounded-xl border p-4 shadow-md transition-all duration-200 text-left select-none',
          job ? 'cursor-pointer' : 'cursor-not-allowed opacity-60',
          isSelected
            ? 'border-amber-400 bg-white ring-2 ring-amber-400/40 shadow-amber-500/10'
            : isRunning
            ? 'border-amber-500/70 bg-amber-950/20 shadow-amber-500/10 animate-pulse'
            : state === 'SUCCEEDED'
            ? 'border-emerald-800/60 bg-white/80 hover:border-emerald-700'
            : state === 'FAILED'
            ? 'border-rose-800/60 bg-white/80 hover:border-rose-700'
            : 'border-slate-200 bg-white/50 hover:border-slate-300 hover:bg-white/80'
        )}
      >
        {/* Top: Icon & Status */}
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2 truncate">
            <div
              className={cn(
                'p-1.5 rounded-lg shrink-0',
                state === 'SUCCEEDED'
                  ? 'bg-emerald-500/10 text-emerald-400'
                  : isRunning
                  ? 'bg-amber-500/10 text-amber-400'
                  : state === 'FAILED'
                  ? 'bg-rose-500/10 text-rose-400'
                  : 'bg-slate-100 text-slate-600'
              )}
            >
              <Icon className="w-4 h-4" />
            </div>
            <h4 className="font-semibold text-slate-900 text-xs truncate">{meta.title}</h4>
          </div>
          <StatusBadge status={state} size="sm" showIcon={false} />
        </div>

        {/* Middle: Description */}
        <p className="mt-2 text-[11px] text-slate-600 font-mono truncate">
          {meta.description}
        </p>

        {/* Bottom: Worker & Duration */}
        <div className="mt-3 pt-2.5 border-t border-slate-200/80 flex items-center justify-between text-[11px] text-slate-600">
          <div className="flex items-center gap-1 truncate max-w-[120px]">
            <Cpu className="w-3 h-3 text-slate-500 shrink-0" />
            <span className="truncate font-mono">
              {job?.workerId ? job.workerId : 'Unassigned'}
            </span>
          </div>

          <div className="flex items-center gap-1 shrink-0 font-mono text-slate-700">
            <Clock className="w-3 h-3 text-slate-500" />
            <span>{job ? formatDuration(job.claimTime, job.completedAt) : '-'}</span>
          </div>
        </div>

        {/* Active execution border glow indicator */}
        {isRunning && (
          <span className="absolute -top-1 -right-1 flex h-2.5 w-2.5">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75" />
            <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-amber-500" />
          </span>
        )}
      </div>
    );
  };

  return (
    <div className="relative py-8 px-4 flex flex-col items-center select-none overflow-x-auto">
      {/* Visual Hierarchy: 6 Rows */}
      <div className="flex flex-col items-center gap-8 relative z-10 w-full max-w-2xl">
        {/* Row 1: Validation */}
        <div className="flex justify-center w-full">
          {renderNode('PROJECT_VALIDATION')}
        </div>

        {/* Connector 1 */}
        <div className="flex flex-col items-center">
          <div className="w-0.5 h-6 bg-slate-700" />
          <div className="w-0 h-0 border-l-4 border-r-4 border-t-4 border-l-transparent border-r-transparent border-t-slate-700" />
        </div>

        {/* Row 2: Dependency Resolution */}
        <div className="flex justify-center w-full">
          {renderNode('DEPENDENCY_RESOLUTION')}
        </div>

        {/* Connector 2 */}
        <div className="flex flex-col items-center">
          <div className="w-0.5 h-6 bg-slate-700" />
          <div className="w-0 h-0 border-l-4 border-r-4 border-t-4 border-l-transparent border-r-transparent border-t-slate-700" />
        </div>

        {/* Row 3: Compilation */}
        <div className="flex justify-center w-full">
          {renderNode('COMPILATION')}
        </div>

        {/* Fork Connectors: Compilation -> [Unit Tests, Static Analysis] */}
        <div className="relative w-full max-w-md h-8 flex items-center justify-center">
          {/* Vertical stem from Compilation */}
          <div className="absolute top-0 left-1/2 -translate-x-1/2 w-0.5 h-3 bg-slate-700" />
          {/* Horizontal split bar */}
          <div className="absolute top-3 left-1/4 right-1/4 h-0.5 bg-slate-700" />
          {/* Left drop to Unit Tests */}
          <div className="absolute top-3 left-1/4 w-0.5 h-4 bg-slate-700" />
          <div className="absolute top-7 left-1/4 -translate-x-1/2 w-0 h-0 border-l-4 border-r-4 border-t-4 border-l-transparent border-r-transparent border-t-slate-700" />
          {/* Right drop to Static Analysis */}
          <div className="absolute top-3 right-1/4 w-0.5 h-4 bg-slate-700" />
          <div className="absolute top-7 right-1/4 translate-x-1/2 w-0 h-0 border-l-4 border-r-4 border-t-4 border-l-transparent border-r-transparent border-t-slate-700" />
        </div>

        {/* Row 4: Parallel Branches (Unit Tests & Static Analysis) */}
        <div className="flex items-center justify-center gap-6 sm:gap-12 w-full">
          {renderNode('UNIT_TESTS')}
          {renderNode('STATIC_ANALYSIS')}
        </div>

        {/* Join Connectors: [Unit Tests, Static Analysis] -> Packaging */}
        <div className="relative w-full max-w-md h-8 flex items-center justify-center">
          {/* Left stem from Unit Tests */}
          <div className="absolute top-0 left-1/4 w-0.5 h-4 bg-slate-700" />
          {/* Right stem from Static Analysis */}
          <div className="absolute top-0 right-1/4 w-0.5 h-4 bg-slate-700" />
          {/* Horizontal join bar */}
          <div className="absolute top-4 left-1/4 right-1/4 h-0.5 bg-slate-700" />
          {/* Center drop to Packaging */}
          <div className="absolute top-4 left-1/2 -translate-x-1/2 w-0.5 h-3 bg-slate-700" />
          <div className="absolute top-7 left-1/2 -translate-x-1/2 w-0 h-0 border-l-4 border-r-4 border-t-4 border-l-transparent border-r-transparent border-t-slate-700" />
        </div>

        {/* Row 5: Packaging */}
        <div className="flex justify-center w-full">
          {renderNode('PACKAGING')}
        </div>

        {/* Connector 5 */}
        <div className="flex flex-col items-center">
          <div className="w-0.5 h-6 bg-slate-700" />
          <div className="w-0 h-0 border-l-4 border-r-4 border-t-4 border-l-transparent border-r-transparent border-t-slate-700" />
        </div>

        {/* Row 6: Report Generation */}
        <div className="flex justify-center w-full">
          {renderNode('REPORT_GENERATION')}
        </div>
      </div>
    </div>
  );
};
