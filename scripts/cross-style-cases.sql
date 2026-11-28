-- Cross-style baseline: case rows with the succeeded game's identity.
-- One row per case, ordered for stable exports. Run with -v run_id=<uuid>.
SELECT json_build_object('runId', r.run_id, 'caseId', r.case_id, 'seed', r.seed,
                         'playerCount', r.player_count, 'state', r.state, 'tableId', r.table_id,
                         'seats', r.seats_json::json, 'result', r.result_json::json, 'gameId', a.game_id)
FROM run_case r
LEFT JOIN LATERAL (SELECT game_id FROM case_attempt WHERE case_id = r.case_id AND state = 'SUCCEEDED'
                    AND game_id IS NOT NULL ORDER BY ordinal DESC LIMIT 1) a ON true
WHERE r.run_id = :'run_id'::uuid ORDER BY r.ordinal;
