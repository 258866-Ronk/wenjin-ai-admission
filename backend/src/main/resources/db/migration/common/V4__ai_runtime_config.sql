create table ai_runtime_config (
    id varchar(32) primary key,
    mode varchar(32) not null,
    provider varchar(80) not null,
    base_url varchar(1000) not null,
    chat_model varchar(200),
    fast_model varchar(200),
    embedding_model varchar(200),
    timeout_seconds integer not null,
    max_retries integer not null,
    max_output_tokens integer not null,
    api_key_ciphertext text,
    api_key_hint varchar(16),
    updated_by varchar(80),
    updated_at timestamp with time zone not null
);
