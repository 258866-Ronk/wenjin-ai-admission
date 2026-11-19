create extension if not exists vector;
alter table knowledge_chunks add column if not exists embedding vector(1536);
create index if not exists idx_knowledge_chunks_embedding on knowledge_chunks using hnsw (embedding vector_cosine_ops);
