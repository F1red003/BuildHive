-- Existing jobs remain immediately eligible. Only retries receive a future timestamp.
ALTER TABLE jobs ADD COLUMN available_at TIMESTAMP WITH TIME ZONE;
