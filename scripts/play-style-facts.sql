-- Analysis report version 6 uses stored case-facts version 5.
SELECT f.facts FROM analysis_case_fact f JOIN run_case r ON r.case_id = f.case_id
WHERE r.run_id = :'run_id'::uuid AND f.analysis_version = '5' ORDER BY r.ordinal;
