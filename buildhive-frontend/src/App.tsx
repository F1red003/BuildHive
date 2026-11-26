import React from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AppLayout } from './components/layout/AppLayout';
import { DashboardPage } from './pages/DashboardPage';
import { SubmitPage } from './pages/SubmitPage';
import { PipelinesPage } from './pages/PipelinesPage';
import { PipelineDetailPage } from './pages/PipelineDetailPage';
import { WorkersPage } from './pages/WorkersPage';

export const App: React.FC = () => {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<AppLayout />}>
          <Route index element={<DashboardPage />} />
          <Route path="submit" element={<SubmitPage />} />
          <Route path="pipelines" element={<PipelinesPage />} />
          <Route path="pipelines/:id" element={<PipelineDetailPage />} />
          <Route path="workers" element={<WorkersPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
};

export default App;
