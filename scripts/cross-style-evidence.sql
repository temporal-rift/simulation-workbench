-- Cross-style baseline: raw events for the same-target void crossing.
-- Only the succeeded game of each case; payloads needed for commitments
-- (SpecialActionPlayed, ActivistDeclarationRecorded), settlements
-- (ScoresUpdated) and seat attribution (FactionAssigned).
-- Run with -v run_id=<uuid>.
SELECT json_build_object('caseId', r.case_id, 'gameId', a.game_id, 'type', e.event_type,
                         'payload', convert_from(p.content, 'UTF8')::json)
FROM run_case r
JOIN LATERAL (SELECT game_id FROM case_attempt WHERE case_id = r.case_id AND state = 'SUCCEEDED'
              AND game_id IS NOT NULL ORDER BY ordinal DESC LIMIT 1) a ON true
JOIN evidence_event e ON e.scope_id = r.case_id AND e.game_id = a.game_id
JOIN evidence_artifact p ON p.digest = e.payload_artifact
WHERE r.run_id = :'run_id'::uuid
  AND e.event_type IN ('FactionAssigned', 'SpecialActionPlayed',
                       'ActivistDeclarationRecorded', 'ScoresUpdated')
ORDER BY r.ordinal, e.source, e.partition_no, e.offset_no;
