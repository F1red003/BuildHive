import React, { useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AlertCircle,
  Archive,
  FileCode2,
  FolderArchive,
  Loader2,
  Upload,
  X,
} from 'lucide-react';
import { submissionsApi } from '../api/client';
import { cn, formatBytes } from '../utils';

const MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB

export const SubmitPage: React.FC = () => {
  const navigate = useNavigate();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [file, setFile] = useState<File | null>(null);
  const [projectName, setProjectName] = useState<string>('');
  const [dragActive, setDragActive] = useState<boolean>(false);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [uploading, setUploading] = useState<boolean>(false);
  const [uploadProgress, setUploadProgress] = useState<number>(0);
  const [serverError, setServerError] = useState<string | null>(null);

  const validateAndSetFile = (candidate: File) => {
    setValidationError(null);
    setServerError(null);

    // 1. Validate file extension (.zip)
    const isZip = candidate.name.toLowerCase().endsWith('.zip') || candidate.type === 'application/zip';
    if (!isZip) {
      setValidationError('Only .zip archives containing a Maven project are accepted.');
      return;
    }

    // 2. Validate file size (< 50 MB)
    if (candidate.size > MAX_FILE_SIZE_BYTES) {
      setValidationError(`Archive size exceeds the 50 MB maximum limit (Current: ${formatBytes(candidate.size)}).`);
      return;
    }

    setFile(candidate);
  };

  const handleDrag = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.type === 'dragenter' || e.type === 'dragover') {
      setDragActive(true);
    } else if (e.type === 'dragleave') {
      setDragActive(false);
    }
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setDragActive(false);

    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      validateAndSetFile(e.dataTransfer.files[0]);
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      validateAndSetFile(e.target.files[0]);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) {
      setValidationError('Please select or drop a Maven project .zip file.');
      return;
    }

    setUploading(true);
    setUploadProgress(0);
    setServerError(null);

    try {
      const response = await submissionsApi.submitProject(
        file,
        projectName.trim() || undefined,
        (percent) => setUploadProgress(percent)
      );

      // Successfully submitted -> redirect directly to pipeline details
      if (response && response.pipelineId) {
        navigate(`/pipelines/${response.pipelineId}`);
      } else {
        navigate('/pipelines');
      }
    } catch (err: unknown) {
      const errorMsg =
        (err as { response?: { data?: { message?: string } } })?.response?.data?.message ||
        (err instanceof Error ? err.message : 'Upload failed. Please verify your connection.');
      setServerError(errorMsg);
      setUploading(false);
    }
  };

  return (
    <div className="max-w-2xl mx-auto space-y-8">
      {/* Header */}
      <div>
        <div className="inline-flex items-center gap-2 rounded-full border border-amber-500/30 bg-amber-500/10 px-3 py-1 text-xs font-semibold text-amber-400 mb-3">
          Step 1: Ingest Project
        </div>
        <h1 className="text-3xl font-bold tracking-tight text-slate-950 flex items-center gap-3">
          <Upload className="w-8 h-8 text-amber-400" />
          Submit Maven Project
        </h1>
        <p className="text-sm text-slate-600 mt-2">
          Upload a ZIP archive of your Java project containing a root <code className="text-amber-300 font-mono text-xs">pom.xml</code>.
          BuildHive will validate the archive, construct the 7-stage DAG, and dispatch jobs to available workers.
        </p>
      </div>

      {/* Submission Form */}
      <form onSubmit={handleSubmit} className="space-y-6">
        {/* Project Name (Optional) */}
        <div className="space-y-2">
          <label htmlFor="projectName" className="block text-sm font-medium text-slate-800">
            Project Name <span className="text-xs text-slate-500 font-normal">(Optional)</span>
          </label>
          <input
            id="projectName"
            type="text"
            value={projectName}
            onChange={(e) => setProjectName(e.target.value)}
            disabled={uploading}
            placeholder="e.g. payments-service (defaults to pom.xml coordinates if empty)"
            className="w-full rounded-xl border border-slate-200 bg-white/60 px-4 py-2.5 text-sm text-slate-900 placeholder:text-slate-500 focus:border-amber-500 focus:outline-none focus:ring-1 focus:ring-amber-500 disabled:opacity-50 transition-colors"
          />
          <p className="text-[11px] text-slate-600">
            If left blank, BuildHive inspects the root pom.xml to derive the project name from its groupId and artifactId.
          </p>
        </div>

        {/* Drag & Drop Zone */}
        <div className="space-y-2">
          <label className="block text-sm font-medium text-slate-800">
            Project Archive (.zip) <span className="text-rose-400">*</span>
          </label>

          <div
            onDragEnter={handleDrag}
            onDragLeave={handleDrag}
            onDragOver={handleDrag}
            onDrop={handleDrop}
            onClick={() => !uploading && fileInputRef.current?.click()}
            className={cn(
              'relative cursor-pointer rounded-2xl border-2 border-dashed p-8 text-center transition-all duration-200',
              dragActive
                ? 'border-amber-400 bg-amber-500/10 scale-[1.01]'
                : file
                ? 'border-emerald-500/60 bg-emerald-950/10'
                : 'border-slate-200 bg-white/40 hover:border-slate-300 hover:bg-white/70',
              uploading && 'cursor-not-allowed opacity-75'
            )}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept=".zip,application/zip"
              onChange={handleFileChange}
              disabled={uploading}
              className="hidden"
            />

            {file ? (
              <div className="flex flex-col items-center gap-3">
                <div className="p-3 rounded-full bg-emerald-500/10 text-emerald-400">
                  <Archive className="w-8 h-8" />
                </div>
                <div>
                  <div className="font-semibold text-slate-900 text-sm">{file.name}</div>
                  <div className="text-xs font-mono text-slate-600 mt-0.5">{formatBytes(file.size)}</div>
                </div>
                {!uploading && (
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      setFile(null);
                      if (fileInputRef.current) fileInputRef.current.value = '';
                    }}
                    className="inline-flex items-center gap-1 text-xs text-rose-400 hover:text-rose-300 transition-colors pt-1"
                  >
                    <X className="w-3.5 h-3.5" />
                    <span>Change archive</span>
                  </button>
                )}
              </div>
            ) : (
              <div className="flex flex-col items-center gap-3">
                <div className="p-3 rounded-full bg-slate-100/80 text-amber-400 group-hover:scale-105 transition-transform">
                  <FolderArchive className="w-8 h-8" />
                </div>
                <div>
                  <div className="text-sm font-semibold text-slate-800">
                    Drop your Maven ZIP archive here, or <span className="text-amber-400 underline">browse</span>
                  </div>
                  <div className="text-xs text-slate-500 mt-1">
                    Maximum allowed file size: 50 MB
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Validation Error Message */}
        {validationError && (
          <div className="rounded-xl border border-rose-800/50 bg-rose-950/30 p-3.5 text-xs text-rose-300 flex items-center gap-2">
            <AlertCircle className="w-4 h-4 shrink-0" />
            <span>{validationError}</span>
          </div>
        )}

        {/* Server Error Message */}
        {serverError && (
          <div className="rounded-xl border border-rose-800/50 bg-rose-950/30 p-3.5 text-xs text-rose-300 flex items-center gap-2">
            <AlertCircle className="w-4 h-4 shrink-0" />
            <div>
              <span className="font-semibold">Submission Rejected: </span>
              {serverError}
            </div>
          </div>
        )}

        {/* Upload Progress Bar */}
        {uploading && (
          <div className="space-y-2 rounded-xl border border-slate-200 bg-white/60 p-4">
            <div className="flex items-center justify-between text-xs text-slate-700">
              <span className="flex items-center gap-1.5 font-medium">
                <Loader2 className="w-3.5 h-3.5 animate-spin text-amber-400" />
                {uploadProgress < 100 ? 'Streaming archive to server...' : 'Validating ZIP security & instantiating DAG...'}
              </span>
              <span className="font-mono text-amber-400 font-semibold">{uploadProgress}%</span>
            </div>
            <div className="w-full bg-slate-100 rounded-full h-2 overflow-hidden">
              <div
                className="bg-amber-500 h-2 rounded-full transition-all duration-200 ease-out"
                style={{ width: `${uploadProgress}%` }}
              />
            </div>
          </div>
        )}

        {/* Action Button */}
        <button
          type="submit"
          disabled={!file || uploading}
          className="w-full inline-flex items-center justify-center gap-2 rounded-xl bg-amber-500 py-3 text-sm font-bold text-slate-950 shadow-md shadow-amber-500/20 hover:bg-amber-400 transition-colors disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
        >
          {uploading ? (
            <>
              <Loader2 className="w-4 h-4 animate-spin" />
              <span>Initiating Build Pipeline...</span>
            </>
          ) : (
            <>
              <Upload className="w-4 h-4" />
              <span>Launch Build Pipeline</span>
            </>
          )}
        </button>
      </form>

      {/* Security & Validation Notice */}
      <div className="rounded-xl border border-slate-200/80 bg-white/30 p-5 space-y-2 text-xs text-slate-600">
        <h3 className="font-semibold text-slate-700 flex items-center gap-1.5">
          <FileCode2 className="w-4 h-4 text-amber-400" />
          Automated Verification Checks
        </h3>
        <ul className="space-y-1 list-disc list-inside text-slate-600">
          <li>Archive header verification &amp; zip-slip path traversal prevention.</li>
          <li>SHA-256 streaming checksum generation for auditability.</li>
          <li>POM root structure inspection &amp; 7-stage DAG blueprint generation.</li>
          <li>Initial <code className="text-slate-700 font-mono">PROJECT_VALIDATION</code> job queued with priority 100.</li>
        </ul>
      </div>
    </div>
  );
};
