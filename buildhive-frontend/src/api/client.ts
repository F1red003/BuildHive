import axios from 'axios';
import type {
  Artifact,
  DashboardMetrics,
  Job,
  Pipeline,
  SubmissionResponse,
  Worker,
} from '../types';

// Axios instance with base URL configured for local proxy
export const apiClient = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
  },
});

// Submissions API
export const submissionsApi = {
  submitProject: async (
    file: File,
    projectName?: string,
    onProgress?: (percent: number) => void
  ): Promise<SubmissionResponse> => {
    const formData = new FormData();
    formData.append('file', file);
    if (projectName && projectName.trim()) {
      formData.append('projectName', projectName.trim());
    }

    const response = await apiClient.post<SubmissionResponse>('/submissions', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
      onUploadProgress: (progressEvent) => {
        if (progressEvent.total && onProgress) {
          const percent = Math.round((progressEvent.loaded * 100) / progressEvent.total);
          onProgress(percent);
        }
      },
    });
    return response.data;
  },
};

// Pipelines API
export const pipelinesApi = {
  getAll: async (): Promise<Pipeline[]> => {
    const response = await apiClient.get<Pipeline[]>('/pipelines');
    return response.data;
  },

  getMetrics: async (): Promise<DashboardMetrics> => {
    const response = await apiClient.get<DashboardMetrics>('/pipelines/metrics');
    return response.data;
  },

  getById: async (id: string): Promise<Pipeline> => {
    const response = await apiClient.get<Pipeline>(`/pipelines/${id}`);
    return response.data;
  },

  getJobs: async (id: string): Promise<Job[]> => {
    const response = await apiClient.get<Job[]>(`/pipelines/${id}/jobs`);
    return response.data;
  },

  cancel: async (id: string): Promise<void> => {
    await apiClient.post(`/pipelines/${id}/cancel`);
  },
};

// Workers API
export const workersApi = {
  getAll: async (): Promise<Worker[]> => {
    const response = await apiClient.get<Worker[]>('/workers');
    return response.data;
  },
};

// Artifacts API
export const artifactsApi = {
  getByPipeline: async (pipelineId: string): Promise<Artifact[]> => {
    const response = await apiClient.get<Artifact[]>(`/pipelines/${pipelineId}/artifacts`);
    return response.data;
  },

  getDownloadUrl: (artifactId: string): string => {
    return `/api/artifacts/${artifactId}/download`;
  },
};
