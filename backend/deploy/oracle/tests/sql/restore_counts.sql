-- One line summarising row counts and a content fingerprint of the TurnoControl data (run as supabase_admin). Used to
-- compare a source database with its restored copy; the fingerprint is an md5 over the records, never the content.
select 'recuentos: usuarios=' || (select count(*) from auth.users) || ' identidades=' || (select count(*) from auth.identities)
  || ' registros=' || (select count(*) from turno.records) || ' historial=' || (select count(*) from turno.record_history)
  || ' mutaciones=' || (select count(*) from turno.applied_mutations) || ' estado=' || (select count(*) from turno.user_state)
  || ' huella=' || coalesce((select md5(string_agg(user_id::text || entity || record_key || version || seq || deleted || coalesce(payload, '~'), ','
       order by user_id, entity, record_key)) from turno.records), 'vacía');
