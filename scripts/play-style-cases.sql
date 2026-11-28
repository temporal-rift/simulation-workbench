SELECT json_build_object('runId', r.run_id, 'caseId', r.case_id, 'seed', r.seed, 'playerCount', r.player_count,
                         'state', r.state, 'seats', r.seats_json::json)
FROM run_case r WHERE r.run_id = :'run_id'::uuid ORDER BY r.ordinal;
