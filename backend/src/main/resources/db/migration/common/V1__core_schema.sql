create table users (
    id varchar(36) primary key,
    email varchar(254) not null unique,
    password_hash varchar(100) not null,
    display_name varchar(80) not null,
    role varchar(20) not null default 'USER',
    anonymous boolean not null default false,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    deleted_at timestamp with time zone
);

create table user_sessions (
    id varchar(36) primary key,
    user_id varchar(36) not null references users(id),
    token_hash varchar(64) not null unique,
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    last_seen_at timestamp with time zone not null
);
create index idx_user_sessions_user on user_sessions(user_id, expires_at);

create table student_profiles (
    id varchar(36) primary key,
    user_id varchar(36) not null unique references users(id),
    province varchar(32),
    exam_year integer,
    score integer,
    rank_value integer,
    rank_source varchar(24),
    subject_type varchar(40),
    primary_subject varchar(20),
    secondary_subjects_json text not null default '[]',
    batch varchar(80),
    candidate_category varchar(40),
    annual_tuition_budget integer,
    preferred_cities_json text not null default '[]',
    excluded_cities_json text not null default '[]',
    preferred_majors_json text not null default '[]',
    excluded_majors_json text not null default '[]',
    school_level_preferences_json text not null default '[]',
    employment_direction varchar(400),
    accept_private boolean,
    accept_cooperative boolean,
    accept_vocational boolean,
    medical_restrictions_json text not null default '[]',
    risk_preference varchar(20),
    special_requirements varchar(1000),
    field_meta_json text not null default '{}',
    version integer not null default 1,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    deleted_at timestamp with time zone
);

create table student_preferences (
    id varchar(36) primary key,
    profile_id varchar(36) not null references student_profiles(id),
    preference_type varchar(40) not null,
    preference_value varchar(200) not null,
    weight decimal(6,3) not null default 1,
    created_at timestamp with time zone not null,
    unique(profile_id, preference_type, preference_value)
);

create table conversations (
    id varchar(36) primary key,
    user_id varchar(36) not null references users(id),
    title varchar(120) not null,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    deleted_at timestamp with time zone
);
create index idx_conversations_user_updated on conversations(user_id, updated_at);

create table conversation_messages (
    id varchar(36) primary key,
    conversation_id varchar(36) not null references conversations(id),
    role varchar(20) not null,
    message_type varchar(32) not null,
    content text not null,
    structured_content_json text,
    created_at timestamp with time zone not null
);
create index idx_messages_conversation_created on conversation_messages(conversation_id, created_at);

create table data_sources (
    id varchar(160) primary key,
    name varchar(300) not null,
    publisher varchar(200),
    source_url varchar(2000) not null,
    source_quality varchar(240) not null,
    source_published_at timestamp with time zone,
    retrieved_at timestamp with time zone not null,
    checksum varchar(64) not null,
    cautions_json text not null default '[]'
);

create table dataset_versions (
    id varchar(36) primary key,
    version_name varchar(120) not null unique,
    status varchar(20) not null,
    province_scope_json text not null default '[]',
    year_scope_json text not null default '[]',
    created_at timestamp with time zone not null,
    activated_at timestamp with time zone
);

create table universities (
    id varchar(36) primary key,
    school_code varchar(40),
    school_name varchar(200) not null,
    city varchar(80),
    province varchar(40),
    ownership_type varchar(40),
    tags_json text not null default '[]',
    unique(school_code, school_name)
);
create index idx_universities_name on universities(school_name);

create table majors (
    id varchar(36) primary key,
    major_code varchar(40),
    major_name varchar(200) not null,
    discipline_category varchar(80),
    description text,
    unique(major_code, major_name)
);

create table university_majors (
    id varchar(36) primary key,
    university_id varchar(36) not null references universities(id),
    major_id varchar(36) references majors(id),
    major_group varchar(200),
    education_level varchar(32),
    unique(university_id, major_id, major_group)
);

create table admission_records (
    id varchar(36) primary key,
    business_key varchar(64) not null unique,
    dataset_version varchar(120) not null,
    province varchar(40) not null,
    admission_year integer not null,
    subject_type varchar(80),
    batch varchar(120),
    school_name varchar(200),
    school_code varchar(60),
    school_tags_json text not null default '[]',
    city varchar(80),
    data_type varchar(60) not null,
    major_name varchar(240),
    major_code varchar(60),
    major_group varchar(240),
    elective_requirement varchar(500),
    required_subjects_json text not null default '[]',
    any_subjects_json text not null default '[]',
    min_score integer,
    avg_score decimal(10,2),
    max_score integer,
    min_rank_start integer,
    min_rank_end integer,
    plan_count integer,
    tuition integer,
    formal_score_scope varchar(80),
    rank_unavailable boolean not null default false,
    score_only boolean not null default false,
    admission_type varchar(100),
    admission_subtype varchar(100),
    medical_restrictions_json text not null default '[]',
    source_id varchar(160) not null references data_sources(id),
    source_url varchar(2000) not null,
    source_quality varchar(240) not null,
    source_published_at timestamp with time zone,
    cautions_json text not null default '[]',
    raw_json text not null,
    checksum varchar(64) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);
create index idx_admission_query on admission_records(province, admission_year, subject_type, batch, data_type);
create index idx_admission_school on admission_records(school_name, major_name);
create index idx_admission_rank on admission_records(province, admission_year, min_rank_end);

create table rank_segments (
    id varchar(36) primary key,
    business_key varchar(64) not null unique,
    dataset_version varchar(120) not null,
    province varchar(40) not null,
    admission_year integer not null,
    subject_type varchar(80) not null,
    score integer not null,
    rank_start integer,
    rank_end integer not null,
    same_score_count integer,
    source_id varchar(160) not null references data_sources(id),
    source_url varchar(2000) not null,
    checksum varchar(64) not null,
    unique(province, admission_year, subject_type, score)
);

create table admission_plans (
    id varchar(36) primary key,
    admission_record_id varchar(36) references admission_records(id),
    province varchar(40) not null,
    admission_year integer not null,
    school_name varchar(200) not null,
    major_name varchar(240),
    plan_count integer,
    tuition integer,
    formal_score_scope varchar(80),
    source_id varchar(160) not null references data_sources(id)
);

create table subject_requirements (
    id varchar(36) primary key,
    admission_record_id varchar(36) not null references admission_records(id),
    requirement_mode varchar(20) not null,
    subjects_json text not null,
    requirement_text varchar(500),
    hard_rule boolean not null default true
);

create table rule_requirements (
    id varchar(36) primary key,
    admission_record_id varchar(36) references admission_records(id),
    rule_type varchar(60) not null,
    operator varchar(20) not null,
    expected_value varchar(500),
    message varchar(1000) not null,
    hard_rule boolean not null default true
);

create table province_thresholds (
    id varchar(36) primary key,
    province varchar(40) not null,
    admission_year integer not null,
    subject_type varchar(80) not null,
    tier varchar(30) not null,
    min_rank_gap integer not null,
    max_rank_gap integer not null,
    min_score_gap integer not null,
    max_score_gap integer not null,
    weights_json text not null,
    unique(province, admission_year, subject_type, tier)
);

create table advisor_runs (
    id varchar(36) primary key,
    conversation_id varchar(36) not null references conversations(id),
    user_id varchar(36) not null references users(id),
    profile_id varchar(36) not null references student_profiles(id),
    idempotency_key varchar(100) not null unique,
    intent varchar(60),
    status varchar(30) not null,
    current_node varchar(60),
    dataset_version varchar(120),
    profile_snapshot_json text,
    state_json text,
    error_category varchar(60),
    error_message varchar(1000),
    cancel_requested boolean not null default false,
    retry_count integer not null default 0,
    created_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    updated_at timestamp with time zone not null
);
create index idx_runs_user_status on advisor_runs(user_id, status, created_at);

create table advisor_run_checkpoints (
    id varchar(36) primary key,
    run_id varchar(36) not null references advisor_runs(id),
    node_name varchar(60) not null,
    sequence_no integer not null,
    state_json text not null,
    created_at timestamp with time zone not null,
    unique(run_id, sequence_no)
);

create table recommendation_results (
    id varchar(36) primary key,
    run_id varchar(36) not null unique references advisor_runs(id),
    profile_id varchar(36) not null references student_profiles(id),
    dataset_version varchar(120) not null,
    strategy varchar(20) not null,
    summary_json text not null,
    created_at timestamp with time zone not null
);

create table recommendation_items (
    id varchar(36) primary key,
    recommendation_id varchar(36) not null references recommendation_results(id),
    admission_record_id varchar(36) not null references admission_records(id),
    tier varchar(30) not null,
    total_score decimal(8,3) not null,
    score_breakdown_json text not null,
    reasons_json text not null,
    evidence_ids_json text not null,
    rule_result_ids_json text not null,
    source_ids_json text not null,
    sort_order integer not null
);

create table risk_items (
    id varchar(36) primary key,
    run_id varchar(36) not null references advisor_runs(id),
    recommendation_item_id varchar(36) references recommendation_items(id),
    risk_code varchar(80) not null,
    level varchar(20) not null,
    title varchar(200) not null,
    detail varchar(1500) not null,
    evidence_ids_json text not null default '[]',
    blocking boolean not null default false
);

create table reports (
    id varchar(36) primary key,
    run_id varchar(36) not null references advisor_runs(id),
    user_id varchar(36) not null references users(id),
    recommendation_id varchar(36) references recommendation_results(id),
    version integer not null,
    status varchar(20) not null,
    title varchar(200) not null,
    report_json text not null,
    dataset_version varchar(120) not null,
    compliance_passed boolean not null,
    compliance_issues_json text not null default '[]',
    reflection_iterations integer not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    deleted_at timestamp with time zone,
    unique(run_id, version)
);

create table report_sections (
    id varchar(36) primary key,
    report_id varchar(36) not null references reports(id),
    section_key varchar(80) not null,
    section_order integer not null,
    content_json text not null,
    unique(report_id, section_key)
);

create table report_evidence (
    id varchar(36) primary key,
    report_id varchar(36) not null references reports(id),
    evidence_type varchar(40) not null,
    evidence_ref varchar(160) not null,
    conclusion_key varchar(120),
    excerpt varchar(1500),
    source_id varchar(160) references data_sources(id),
    rule_result_id varchar(36),
    dataset_version varchar(120) not null
);

create table import_batches (
    id varchar(36) primary key,
    dataset_version varchar(120) not null,
    source_path varchar(1000) not null,
    mode varchar(20) not null,
    dry_run boolean not null,
    status varchar(20) not null,
    scanned_files integer not null default 0,
    successful_files integer not null default 0,
    failed_files integer not null default 0,
    read_records integer not null default 0,
    inserted_records integer not null default 0,
    updated_records integer not null default 0,
    skipped_records integer not null default 0,
    duplicate_records integer not null default 0,
    statistics_json text not null default '{}',
    created_at timestamp with time zone not null,
    completed_at timestamp with time zone
);

create table import_failures (
    id varchar(36) primary key,
    batch_id varchar(36) not null references import_batches(id),
    source_file varchar(1000) not null,
    record_index integer,
    reason varchar(2000) not null,
    raw_excerpt varchar(2000),
    created_at timestamp with time zone not null
);

create table knowledge_documents (
    id varchar(36) primary key,
    title varchar(400) not null,
    document_type varchar(60) not null,
    province varchar(40),
    admission_year integer,
    batch varchar(120),
    school_name varchar(200),
    major_name varchar(200),
    source_id varchar(160) not null references data_sources(id),
    source_quality varchar(240) not null,
    published_at timestamp with time zone,
    checksum varchar(64) not null unique,
    created_at timestamp with time zone not null
);

create table knowledge_chunks (
    id varchar(36) primary key,
    document_id varchar(36) not null references knowledge_documents(id),
    chunk_index integer not null,
    content text not null,
    metadata_json text not null,
    embedding_text text,
    unique(document_id, chunk_index)
);

create table audit_logs (
    id varchar(36) primary key,
    user_id_masked varchar(80),
    action varchar(120) not null,
    resource_type varchar(80),
    resource_id varchar(80),
    trace_id varchar(80),
    status varchar(30) not null,
    details_json text not null default '{}',
    created_at timestamp with time zone not null
);

