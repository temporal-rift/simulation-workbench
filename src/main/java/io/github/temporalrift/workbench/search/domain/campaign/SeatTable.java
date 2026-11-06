package io.github.temporalrift.workbench.search.domain.campaign;

import java.util.List;

/**
 * A named assignment of one policy to each seat, indexed by seat.
 *
 * @param seats the policy of each seat
 */
public record SeatTable(String id, List<PolicyRef> seats) {

    public SeatTable {
        seats = List.copyOf(seats);
    }
}
