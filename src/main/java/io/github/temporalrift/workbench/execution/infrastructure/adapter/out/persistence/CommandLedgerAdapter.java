package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.temporalrift.workbench.execution.domain.command.CommandIntent;
import io.github.temporalrift.workbench.execution.domain.command.Slot;
import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;

/** PostgreSQL command ledger: one row per decision slot, claimed atomically before anything is sent. */
public class CommandLedgerAdapter implements CommandLedger {

    private static final String COLUMNS = "case_id, seat_index, window_key, attempt_id, request, status, outcome";
    private static final String SELECT_SLOT = "SELECT " + COLUMNS;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public CommandLedgerAdapter(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public CommandIntent begin(SlotId slot, UUID attemptId, String request, Instant now) {
        return transactions.execute(status -> {
            var inserted = jdbc.update(
                    "INSERT INTO case_command (case_id, seat_index, window_key, attempt_id, request, status,"
                            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'SENT', ?, ?)"
                            + " ON CONFLICT (case_id, seat_index, window_key) DO NOTHING",
                    slot.caseId(),
                    slot.seatIndex(),
                    slot.windowKey(),
                    attemptId,
                    request,
                    Rows.timestamp(now),
                    Rows.timestamp(now));
            if (inserted == 1) {
                return new CommandIntent.Send();
            }
            var existing = jdbc.query(
                            SELECT_SLOT + " FROM case_command WHERE case_id = ? AND seat_index = ?"
                                    + " AND window_key = ? FOR UPDATE",
                            (rs, i) -> slot(rs),
                            slot.caseId(),
                            slot.seatIndex(),
                            slot.windowKey())
                    .getFirst();
            return switch (existing.status()) {
                case ACCEPTED -> new CommandIntent.AlreadyAccepted(existing);
                case SENT -> new CommandIntent.InDoubt(existing);
                case NOT_SPENT -> {
                    jdbc.update(
                            "UPDATE case_command SET attempt_id = ?, request = ?, status = 'SENT', outcome = NULL,"
                                    + " updated_at = ? WHERE case_id = ? AND seat_index = ? AND window_key = ?",
                            attemptId,
                            request,
                            Rows.timestamp(now),
                            slot.caseId(),
                            slot.seatIndex(),
                            slot.windowKey());
                    yield new CommandIntent.Send();
                }
            };
        });
    }

    @Override
    public void resolve(SlotId slot, SlotStatus status, String outcome, Instant now) {
        jdbc.update(
                "UPDATE case_command SET status = ?, outcome = ?, updated_at = ? WHERE case_id = ?"
                        + " AND seat_index = ? AND window_key = ?",
                status.name(),
                outcome,
                Rows.timestamp(now),
                slot.caseId(),
                slot.seatIndex(),
                slot.windowKey());
    }

    @Override
    public Optional<Slot> find(SlotId slot) {
        return jdbc
                .query(
                        SELECT_SLOT + " FROM case_command WHERE case_id = ? AND seat_index = ?" + " AND window_key = ?",
                        (rs, i) -> slot(rs),
                        slot.caseId(),
                        slot.seatIndex(),
                        slot.windowKey())
                .stream()
                .findFirst();
    }

    @Override
    public List<Slot> accepted(UUID caseId) {
        return jdbc.query(
                SELECT_SLOT + " FROM case_command WHERE case_id = ? AND status = 'ACCEPTED'"
                        + " ORDER BY window_key COLLATE \"C\", seat_index",
                (rs, i) -> slot(rs),
                caseId);
    }

    @Override
    public List<Slot> inDoubt(UUID caseId) {
        return jdbc.query(
                SELECT_SLOT + " FROM case_command WHERE case_id = ? AND status = 'SENT'"
                        + " ORDER BY window_key COLLATE \"C\", seat_index",
                (rs, i) -> slot(rs),
                caseId);
    }

    @Override
    public void reset(UUID caseId) {
        jdbc.update("DELETE FROM case_command WHERE case_id = ?", caseId);
    }

    private static Slot slot(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Slot(
                new SlotId(rs.getObject("case_id", UUID.class), rs.getInt("seat_index"), rs.getString("window_key")),
                rs.getObject("attempt_id", UUID.class),
                rs.getString("request"),
                SlotStatus.valueOf(rs.getString("status")),
                rs.getString("outcome"));
    }
}
