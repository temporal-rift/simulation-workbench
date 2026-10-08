package io.github.temporalrift.workbench.execution.domain.command;

/** What recording the intent to send into a slot decided. */
public sealed interface CommandIntent {

    /** The slot was free; send the command. */
    record Send() implements CommandIntent {}

    /** The slot already holds an accepted command; do not send. */
    record AlreadyAccepted(Slot slot) implements CommandIntent {}

    /** A command was sent and never resolved; reconcile before anything is sent. */
    record InDoubt(Slot slot) implements CommandIntent {}
}
