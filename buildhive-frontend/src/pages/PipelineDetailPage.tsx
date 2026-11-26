import React, { useCallback, useEffect, useState } from 'react';
import { NavLink, useParams } from 'react-router-dom';
import {
  AlertCircle,
  Archive,
  ArrowLeft,
  Ban,
  Check,
  Copy,
  Download,
  FileCode,
  GitFork,
  Loader2,
  Package,
  RefreshCw,
} from 'lucide-react';
import { artifactsApi, pipelinesApi } from '../api/client';
import { StatusBadge } from '../components/common/StatusBadge';
import { DagVisualizer } from '../components/dag/DagVisualizer';
import { JobLogViewer } from '../components/JobLogViewer';
import type { Artifact, Job, Pipeline } from '../types';
import { cn, formatBytes, formatDateTime, formatDuration } from '../utils';

export const PipelineDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  return id ? <PipelineDetail key={id} id={id} /> : null;
};

function PipelineDetail({ id }: { id: string }) {
  const [pipeline, setPipeline] = useState<Pipeline | null>(null);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [artifacts, setArtifacts] = useState<Artifact[]>([]);
  const [selectedJob, setSelectedJob] = useState<Job | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [cancelling, setCancelling] = useState<boolean>(false);
  const [copiedHashId, setCopiedHashId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const fetchPipelineDetails = useCallback(async (isManual = false) => {
    if (isManual) setRefreshing(true);
    setError(null);

    try {
      const [pipelineData, jobsData, artifactsData] = await Promise.all([
        pipelinesApi.getById(id),
        pipelinesApi.getJobs(id),
        artifactsApi.getByPipeline(id).catch(() => []),
      ]);
      setPipeline(pipelineData);
      setJobs(jobsData);
      setArtifacts(artifactsData);

      // Keep selected job reference up to date
      setSelectedJob((prev) => (prev ? jobsData.find((j) => j.id === prev.id) || prev : null));
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to fetch pipeline data';
      setError(msg);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [id]);

  useEffect(() => {
    const initial = setTimeout(fetchPipelineDetails, 0);
    return () => clearTimeout(initial);
  }, [fetchPipelineDetails]);

  const active = pipeline?.status === 'RUNNING' || pipeline?.status === 'PENDING';
  useEffect(() => {
    if (!active) return;
    const interval = setInterval(fetchPipelineDetails, 3000);
    return () => clearInterval(interval);
  }, [active, fetchPipelineDetails]);

  const handleCancelPipeline = async () => {
    if (!id) return;
    const confirmed = window.confirm(
      'Are you sure you want to cancel this build pipeline? All running and blocked jobs will be terminated.'
    );
    if (!confirmed) return;

    setCancelling(true);
    try {
      await pipelinesApi.cancel(id);
      await fetchPipelineDetails();
    } catch (err: unknown) {
      alert('Failed to cancel pipeline: ' + (err instanceof Error ? err.message : 'Unknown error'));
    } finally {
      setCancelling(false);
    }
  };

  const handleCopySha256 = (sha256: string, artifactId: string) => {
    navigator.clipboard.writeText(sha256);
    setCopiedHashId(artifactId);
    setTimeout(() => {
      setCopiedHashId(null);
    }, 2000);
  };

  const isTerminal = pipeline?.status === 'SUCCEEDED' || pipeline?.status === 'FAILED' || pipeline?.status === 'CANCELLED';
  const analysis = jobs.find((job) => job.jobType === 'STATIC_ANALYSIS');
  const analysisReports = artifacts.filter((artifact) =>
    artifact.artifactType === 'CHECKSTYLE_REPORT' && artifact.jobId === analysis?.id);

  if (loading) {
    return (
      <div className="py-24 text-center space-y-3">
        <Loader2 className="w-8 h-8 animate-spin text-amber-400 mx-auto" />
        <div className="text-sm text-slate-600">Loading pipeline DAG execution...</div>
      </div>
    );
  }

  if (error || !pipeline) {
    return (
      <div className="rounded-xl border border-rose-800/50 bg-rose-950/20 p-8 text-center space-y-4">
        <AlertCircle className="w-8 h-8 text-rose-400 mx-auto" />
        <h2 className="text-base font-semibold text-rose-300">Pipeline Not Found</h2>
        <p className="text-xs text-slate-600">{error || 'The requested pipeline could not be loaded.'}</p>
        <NavLink
          to="/pipelines"
          className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-slate-100 text-slate-800 text-xs hover:bg-slate-200 transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          Back to Pipelines
        </NavLink>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      {/* Back Link & Action Bar */}
      <div className="flex items-center justify-between">
        <NavLink
          to="/pipelines"
          className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-600 hover:text-slate-800 transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          <span>Back to Pipelines</span>
        </NavLink>

        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => fetchPipelineDetails(true)}
            disabled={refreshing}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg border border-slate-300 bg-slate-100/80 hover:bg-slate-200 text-slate-800 transition-colors disabled:opacity-50"
          >
            <RefreshCw className={cn('w-3.5 h-3.5', refreshing && 'animate-spin text-amber-400')} />
            <span>Refresh</span>
          </button>

          {!isTerminal && (
            <button
              type="button"
              onClick={handleCancelPipeline}
              disabled={cancelling}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg border border-rose-800 bg-rose-950/50 hover:bg-rose-900/60 text-rose-300 transition-colors disabled:opacity-50"
            >
              {cancelling ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Ban className="w-3.5 h-3.5" />}
              <span>Cancel Pipeline</span>
            </button>
          )}
        </div>
      </div>

      {/* 1. Header Details Panel */}
      <div className="rounded-2xl border border-slate-200 bg-white/60 p-6 shadow-xl backdrop-blur-sm">
        <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-6">
          <div className="space-y-1">
            <div className="flex items-center gap-3 flex-wrap">
              <h1 className="text-2xl font-bold tracking-tight text-slate-950">
                {pipeline.projectName || 'Maven Build Pipeline'}
              </h1>
              <StatusBadge status={pipeline.status} size="md" />
            </div>
            <div className="text-xs font-mono text-slate-600 flex items-center gap-2 flex-wrap">
              <span>Pipeline ID: {pipeline.id}</span>
              {pipeline.cancellationRequested && (
                <span className="text-rose-400 font-semibold">(Cancellation Requested)</span>
              )}
            </div>
          </div>

          {/* Quick Metrics Bar */}
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-4 border-t lg:border-t-0 lg:border-l border-slate-200 pt-4 lg:pt-0 lg:pl-6 text-xs">
            <div>
              <span className="text-slate-600 block mb-0.5">Total Duration</span>
              <span className="font-mono font-semibold text-slate-800 text-sm">
                {formatDuration(pipeline.startedAt, pipeline.completedAt)}
              </span>
            </div>

            <div>
              <span className="text-slate-600 block mb-0.5">Submitted</span>
              <span className="font-mono text-slate-700">
                {formatDateTime(pipeline.createdAt)}
              </span>
            </div>

            <div>
              <span className="text-slate-600 block mb-0.5">Completed</span>
              <span className="font-mono text-slate-700">
                {pipeline.completedAt ? formatDateTime(pipeline.completedAt) : 'In Progress'}
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* 2. Interactive DAG Visualizer */}
      <div className="rounded-2xl border border-slate-200/80 bg-white/40 p-6 shadow-xl">
        <div className="dag-section-heading flex items-center justify-between pb-4 border-b border-slate-200">
          <div className="flex items-center gap-2">
            <GitFork className="w-5 h-5 text-amber-400" />
            <h2 className="font-semibold text-slate-900 text-base">Pipeline Execution Graph (DAG)</h2>
          </div>
          <span className="text-xs text-slate-600">
            Click any stage node to view console output and worker details
          </span>
        </div>

        <DagVisualizer
          jobs={jobs}
          selectedJobId={selectedJob?.id || null}
          onSelectJob={(job) => setSelectedJob(job)}
        />
      </div>

      {analysis && (analysisReports.length > 0 || analysis.state === 'FAILED') && (
        <section className="rounded-xl border border-slate-200 bg-white p-5">
          <div className="flex flex-wrap items-center gap-3">
            <h2 className="text-sm font-semibold text-slate-900">Checkstyle results</h2>
            <StatusBadge status={analysis.state} size="sm" />
          </div>
          {analysisReports.length > 0 ? (
            <ul className="mt-3 space-y-1 text-sm text-slate-700">
              {analysisReports.map((report) => (
                <li key={report.id}>
                  <a className="underline" href={artifactsApi.getDownloadUrl(report.id)} download>
                    {report.fileName}
                  </a>
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-3 text-sm text-slate-700">No Checkstyle report was produced for this stage.</p>
          )}
          <button type="button" className="mt-3 text-sm underline" onClick={() => setSelectedJob(analysis)}>
            Open analysis log
          </button>
        </section>
      )}

      {/* 3. Generated Artifacts Section */}
      <div className="rounded-2xl border border-slate-200/80 bg-white/40 p-6 shadow-xl space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Package className="w-5 h-5 text-amber-400" />
            <h2 className="font-semibold text-slate-900 text-base">Generated Build Artifacts</h2>
            <span className="text-xs px-2 py-0.5 rounded-full bg-slate-100 text-slate-600 font-mono">
              {artifacts.length}
            </span>
          </div>
        </div>

        {artifacts.length === 0 ? (
          <div className="p-8 text-center text-xs text-slate-600 border border-dashed border-slate-200/80 rounded-xl">
            {pipeline.status === 'RUNNING'
              ? 'Artifacts (JARs, consolidated reports) will appear here once packaging completes.'
              : 'No output artifacts recorded for this pipeline execution.'}
          </div>
        ) : (
          <div className="divide-y divide-slate-200/60 rounded-xl border border-slate-200/80 overflow-hidden bg-slate-50/40">
            {artifacts.map((artifact) => (
              <div
                key={artifact.id}
                className="p-4 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 hover:bg-slate-100/30 transition-colors"
              >
                <div className="flex items-center gap-3">
                  <div className="p-2.5 rounded-lg bg-amber-500/10 text-amber-400 shrink-0">
                    {artifact.fileName.endsWith('.jar') ? (
                      <Archive className="w-5 h-5" />
                    ) : (
                      <FileCode className="w-5 h-5" />
                    )}
                  </div>
                  <div>
                    <div className="font-semibold text-slate-900 text-sm">{artifact.fileName}</div>
                    <div className="text-xs text-slate-600 font-mono mt-0.5 flex items-center gap-3 flex-wrap">
                      <span>Size: {formatBytes(artifact.fileSizeBytes)}</span>
                      <span>Type: {artifact.artifactType}</span>
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-2 self-end sm:self-center">
                  {/* Copy SHA-256 Button */}
                  <button
                    type="button"
                    onClick={() => handleCopySha256(artifact.sha256, artifact.id)}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-300 bg-slate-100/80 hover:bg-slate-200 text-slate-700 text-xs transition-colors"
                    title={`SHA-256: ${artifact.sha256}`}
                  >
                    {copiedHashId === artifact.id ? (
                      <>
                        <Check className="w-3.5 h-3.5 text-emerald-400" />
                        <span className="text-emerald-400">Copied!</span>
                      </>
                    ) : (
                      <>
                        <Copy className="w-3.5 h-3.5" />
                        <span>Copy SHA-256</span>
                      </>
                    )}
                  </button>

                  {/* Download Artifact Button */}
                  <a
                    href={artifactsApi.getDownloadUrl(artifact.id)}
                    download
                    className="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg bg-amber-500 hover:bg-amber-400 text-slate-950 font-semibold text-xs shadow-sm transition-colors"
                  >
                    <Download className="w-3.5 h-3.5" />
                    <span>Download</span>
                  </a>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Drawer: Job Details and Console Output */}
      <JobLogViewer
        job={selectedJob}
        onClose={() => setSelectedJob(null)}
      />
    </div>
  );
};
