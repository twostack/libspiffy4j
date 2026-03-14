package org.twostack.libspiffy4j.config;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.javadsl.EventHandler;
import org.apache.pekko.persistence.typed.javadsl.EventSourcedBehavior;
import org.twostack.libspiffy4j.serialization.SpiffyEvent;

/**
 * Test-only EventSourcedBehavior for validating persist + recovery.
 */
public class SmokeAggregate
        extends EventSourcedBehavior<SmokeAggregate.SmokeCommand, SmokeAggregate.SmokeEvent, SmokeAggregate.SmokeState> {

    // ---- Commands ----
    public sealed interface SmokeCommand permits Ping {}

    public record Ping(ActorRef<String> replyTo) implements SmokeCommand {}

    // ---- Events ----
    public sealed interface SmokeEvent extends SpiffyEvent permits Pinged {}

    public record Pinged() implements SmokeEvent {}

    // ---- State ----
    public record SmokeState(int pingCount) {
        public SmokeState() {
            this(0);
        }
    }

    public static Behavior<SmokeCommand> create(PersistenceId persistenceId) {
        return new SmokeAggregate(persistenceId);
    }

    private SmokeAggregate(PersistenceId persistenceId) {
        super(persistenceId);
    }

    @Override
    public SmokeState emptyState() {
        return new SmokeState();
    }

    @Override
    public CommandHandler<SmokeCommand, SmokeEvent, SmokeState> commandHandler() {
        return newCommandHandlerBuilder()
                .forAnyState()
                .onCommand(Ping.class, (state, cmd) ->
                        Effect().persist(new Pinged())
                                .thenReply(cmd.replyTo(), s -> "Pong:" + s.pingCount()))
                .build();
    }

    @Override
    public EventHandler<SmokeState, SmokeEvent> eventHandler() {
        return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(Pinged.class, (state, evt) -> new SmokeState(state.pingCount() + 1))
                .build();
    }
}
