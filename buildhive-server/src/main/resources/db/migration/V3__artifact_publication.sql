ALTER TABLE artifacts ADD COLUMN publication_key VARCHAR(64);
CREATE UNIQUE INDEX uq_artifact_publication_key ON artifacts(publication_key);
