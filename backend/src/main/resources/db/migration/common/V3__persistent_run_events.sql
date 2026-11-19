create table advisor_run_events (
    run_id varchar(36) not null references advisor_runs(id),
    event_id bigint not null,
    event_name varchar(80) not null,
    event_data_json text not null,
    created_at timestamp with time zone not null,
    primary key (run_id, event_id)
);

create index idx_run_events_replay on advisor_run_events(run_id, event_id);
