-- Read-only: server settings that compose.yaml must have applied to turnocontrol-db (not meaningful in a restore drill).
select 'sin registro de sentencias SQL' as check_name, current_setting('log_statement') = 'none' and current_setting('log_min_error_statement') = 'panic' and current_setting('log_min_duration_statement') = '-1' as ok
union all select 'shared_buffers 512MB', current_setting('shared_buffers') = '512MB'
union all select 'max_connections 60', current_setting('max_connections') = '60';
