package org.twostack.libspiffy4j.saga;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.javadsl.CommandHandlerBuilder;
import org.apache.pekko.persistence.typed.javadsl.EventHandler;
import org.apache.pekko.persistence.typed.javadsl.EventSourcedBehavior;
import org.twostack.libspiffy4j.aggregate.wallet.WalletAggregate;
import org.twostack.libspiffy4j.aggregate.wallet.WalletCommand;
import org.twostack.libspiffy4j.aggregate.wallet.WalletReply;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Persistent saga coordinating UTXO lifecycle between wallet and channel aggregates.
 *
 * <p>State machine:
 * IDLE → RESERVING → RESERVED → OPEN → SPENDING → COMPLETED
 *                                    ↘ RELEASING → COMPLETED
 *             ↘ FAILED
 */
public class ChannelWalletSaga
        extends EventSourcedBehavior<ChannelWalletSagaCommand, ChannelWalletSagaEvent, ChannelWalletSagaState> {

    public static final EntityTypeKey<ChannelWalletSagaCommand> ENTITY_TYPE_KEY =
            EntityTypeKey.create(ChannelWalletSagaCommand.class, "ChannelWalletSaga");

    private final ActorContext<ChannelWalletSagaCommand> context;
    private final ClusterSharding sharding;
    private final Duration walletAskTimeout;

    public static Behavior<ChannelWalletSagaCommand> create(
            PersistenceId persistenceId, ClusterSharding sharding, Duration walletAskTimeout) {
        return new ChannelWalletSaga(persistenceId, sharding, walletAskTimeout);
    }

    private ChannelWalletSaga(PersistenceId persistenceId, ClusterSharding sharding, Duration walletAskTimeout) {
        super(persistenceId);
        this.context = null; // set via createReceive
        this.sharding = sharding;
        this.walletAskTimeout = walletAskTimeout;
    }

    /**
     * Factory using ActorContext for message adapter registration.
     */
    public static Behavior<ChannelWalletSagaCommand> create(
            PersistenceId persistenceId, ActorContext<ChannelWalletSagaCommand> ctx,
            ClusterSharding sharding, Duration walletAskTimeout) {
        return new ChannelWalletSagaWithContext(persistenceId, ctx, sharding, walletAskTimeout);
    }

    @Override
    public ChannelWalletSagaState emptyState() {
        return new ChannelWalletSagaState();
    }

    @Override
    public Set<String> tagsFor(ChannelWalletSagaEvent event) {
        return Set.of("channel-wallet-saga");
    }

    @Override
    public CommandHandler<ChannelWalletSagaCommand, ChannelWalletSagaEvent, ChannelWalletSagaState> commandHandler() {
        CommandHandlerBuilder<ChannelWalletSagaCommand, ChannelWalletSagaEvent, ChannelWalletSagaState> builder =
                newCommandHandlerBuilder();

        // IDLE: accept initiation
        builder.forState(s -> s.getPhase() == ChannelWalletSagaState.Phase.IDLE)
                .onCommand(ChannelWalletSagaCommand.InitiateFundingCommand.class, this::onInitiateFunding)
                .onAnyCommand((state, cmd) -> Effect().none());

        // RESERVING: waiting for wallet reply
        builder.forState(s -> s.getPhase() == ChannelWalletSagaState.Phase.RESERVING)
                .onCommand(ChannelWalletSagaCommand.WrappedWalletReply.class, this::onWalletReplyDuringReserving)
                .onCommand(ChannelWalletSagaCommand.HandleChannelFailedCommand.class, this::onChannelFailedDuringReserving)
                .onAnyCommand((state, cmd) -> Effect().none());

        // RESERVED: channel not yet open
        builder.forState(s -> s.getPhase() == ChannelWalletSagaState.Phase.RESERVED)
                .onCommand(ChannelWalletSagaCommand.ConfirmChannelOpenedCommand.class, this::onChannelOpened)
                .onCommand(ChannelWalletSagaCommand.HandleChannelFailedCommand.class, this::onChannelFailedAfterReserved)
                .onCommand(ChannelWalletSagaCommand.HandleRefundClaimedCommand.class, this::onRefundClaimedAfterReserved)
                .onAnyCommand((state, cmd) -> Effect().none());

        // OPEN: channel is live
        builder.forState(s -> s.getPhase() == ChannelWalletSagaState.Phase.OPEN)
                .onCommand(ChannelWalletSagaCommand.HandleChannelClosedCommand.class, this::onChannelClosed)
                .onCommand(ChannelWalletSagaCommand.HandleRefundClaimedCommand.class, this::onRefundClaimedWhenOpen)
                .onCommand(ChannelWalletSagaCommand.HandleChannelFailedCommand.class, this::onChannelFailedWhenOpen)
                .onAnyCommand((state, cmd) -> Effect().none());

        // SPENDING/RELEASING: waiting for wallet confirmation
        builder.forState(s -> s.getPhase() == ChannelWalletSagaState.Phase.SPENDING
                || s.getPhase() == ChannelWalletSagaState.Phase.RELEASING)
                .onCommand(ChannelWalletSagaCommand.WrappedWalletReply.class, this::onWalletReplyDuringFinalization)
                .onAnyCommand((state, cmd) -> Effect().none());

        // Terminal states
        builder.forState(s -> s.getPhase() == ChannelWalletSagaState.Phase.COMPLETED
                || s.getPhase() == ChannelWalletSagaState.Phase.FAILED)
                .onAnyCommand((state, cmd) -> Effect().none());

        return builder.build();
    }

    // --- Command handlers ---

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelWalletSagaEvent, ChannelWalletSagaState>
            onInitiateFunding(ChannelWalletSagaState state, ChannelWalletSagaCommand.InitiateFundingCommand cmd) {
        var event = new ChannelWalletSagaEvent.FundingInitiatedEvent(
                cmd.channelId(), cmd.walletId(), cmd.utxoKey(), cmd.amount(), cmd.expiresAt(), Instant.now());
        return Effect().persist(event).thenRun(newState -> sendReserveUtxo(newState));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelWalletSagaEvent, ChannelWalletSagaState>
            onWalletReplyDuringReserving(ChannelWalletSagaState state, ChannelWalletSagaCommand.WrappedWalletReply cmd) {
        if (cmd.reply() instanceof WalletReply.Success) {
            var event = new ChannelWalletSagaEvent.UtxoReservedEvent(
                    state.getChannelId(), state.getUtxoKey(), Instant.now());
            return Effect().persist(event);
        } else {
            String reason = (cmd.reply() instanceof WalletReply.Failure f) ? f.reason() : "Unknown";
            var event = new ChannelWalletSagaEvent.UtxoReservationFailedEvent(
                    state.getChannelId(), state.getUtxoKey(), reason, Instant.now());
            return Effect().persist(event);
        }
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelWalletSagaEvent, ChannelWalletSagaState>
            onChannelFailedDuringReserving(ChannelWalletSagaState state, ChannelWalletSagaCommand.HandleChannelFailedCommand cmd) {
        // Still reserving — will handle when reply arrives; for now just mark failed
        var event = new ChannelWalletSagaEvent.UtxoReservationFailedEvent(
                state.getChannelId(), state.getUtxoKey(), cmd.reason(), Instant.now());
        return Effect().persist(event);
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelWalletSagaEvent, ChannelWalletSagaState>
            onChannelOpened(ChannelWalletSagaState state, ChannelWalletSagaCommand.ConfirmChannelOpenedCommand cmd) {
        var event = new ChannelWalletSagaEvent.ChannelOpenConfirmedEvent(
                state.getChannelId(), Instant.now());
        return Effect().persist(event);
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelWalletSagaEvent, ChannelWalletSagaState>
            onChannelFailedAfterReserved(ChannelWalletSagaState state, ChannelWalletSagaCommand.HandleChannelFailedCommand cmd) {
        // Release the reserved UTXO
        var event = new ChannelWalletSagaEvent.UtxoReleasedEvent(
                state.getChannelId(), state.getUtxoKey(), Instant.now());
        return Effect().persist(event).thenRun(newState -> sendReleaseUtxo(state));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelWalletSagaEvent, ChannelWalletSagaState>
            onRefundClaimedAfterReserved(ChannelWalletSagaState state, ChannelWalletSagaCommand.HandleRefundClaimedCommand cmd) {
        var event = new ChannelWalletSagaEvent.UtxoReleasedEvent(
                state.getChannelId(), state.getUtxoKey(), Instant.now());
        return Effect().persist(event).thenRun(newState -> sendReleaseUtxo(state));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelWalletSagaEvent, ChannelWalletSagaState>
            onChannelClosed(ChannelWalletSagaState state, ChannelWalletSagaCommand.HandleChannelClosedCommand cmd) {
        var event = new ChannelWalletSagaEvent.UtxoMarkedSpentEvent(
                state.getChannelId(), state.getUtxoKey(), Instant.now());
        return Effect().persist(event).thenRun(newState -> sendMarkUtxoSpent(state));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelWalletSagaEvent, ChannelWalletSagaState>
            onRefundClaimedWhenOpen(ChannelWalletSagaState state, ChannelWalletSagaCommand.HandleRefundClaimedCommand cmd) {
        var event = new ChannelWalletSagaEvent.UtxoReleasedEvent(
                state.getChannelId(), state.getUtxoKey(), Instant.now());
        return Effect().persist(event).thenRun(newState -> sendReleaseUtxo(state));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelWalletSagaEvent, ChannelWalletSagaState>
            onChannelFailedWhenOpen(ChannelWalletSagaState state, ChannelWalletSagaCommand.HandleChannelFailedCommand cmd) {
        var event = new ChannelWalletSagaEvent.UtxoReleasedEvent(
                state.getChannelId(), state.getUtxoKey(), Instant.now());
        return Effect().persist(event).thenRun(newState -> sendReleaseUtxo(state));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelWalletSagaEvent, ChannelWalletSagaState>
            onWalletReplyDuringFinalization(ChannelWalletSagaState state, ChannelWalletSagaCommand.WrappedWalletReply cmd) {
        // Already persisted the outcome event; wallet reply is fire-and-forget confirmation
        return Effect().none();
    }

    // --- Wallet interaction (fire-and-forget via sharding) ---

    private void sendReserveUtxo(ChannelWalletSagaState state) {
        if (sharding == null) return;
        var walletRef = sharding.entityRefFor(WalletAggregate.ENTITY_TYPE_KEY, state.getWalletId());
        walletRef.tell(new WalletCommand.ReserveUtxoCommand(
                state.getWalletId(), state.getUtxoKey(), state.getChannelId(),
                Instant.now().plus(Duration.ofHours(1)), 1, "channel-funding",
                getReplyAdapter()));
    }

    private void sendReleaseUtxo(ChannelWalletSagaState state) {
        if (sharding == null) return;
        var walletRef = sharding.entityRefFor(WalletAggregate.ENTITY_TYPE_KEY, state.getWalletId());
        walletRef.tell(new WalletCommand.ReleaseUtxoCommand(
                state.getWalletId(), state.getUtxoKey(), getReplyAdapter()));
    }

    private void sendMarkUtxoSpent(ChannelWalletSagaState state) {
        if (sharding == null) return;
        var walletRef = sharding.entityRefFor(WalletAggregate.ENTITY_TYPE_KEY, state.getWalletId());
        walletRef.tell(new WalletCommand.MarkUtxoSpentCommand(
                state.getWalletId(), state.getUtxoKey(), getReplyAdapter()));
    }

    /**
     * Returns an adapter that wraps WalletReply into WrappedWalletReply.
     * Subclass with context overrides this.
     */
    protected ActorRef<WalletReply> getReplyAdapter() {
        // Without context, we can't create a message adapter — return a dead letter ref
        return null;
    }

    // --- Event handler ---

    @Override
    public EventHandler<ChannelWalletSagaState, ChannelWalletSagaEvent> eventHandler() {
        return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(ChannelWalletSagaEvent.FundingInitiatedEvent.class,
                        (state, evt) -> state.applyFundingInitiated(evt))
                .onEvent(ChannelWalletSagaEvent.UtxoReservedEvent.class,
                        (state, evt) -> state.applyUtxoReserved(evt))
                .onEvent(ChannelWalletSagaEvent.UtxoReservationFailedEvent.class,
                        (state, evt) -> state.applyUtxoReservationFailed(evt))
                .onEvent(ChannelWalletSagaEvent.ChannelOpenConfirmedEvent.class,
                        (state, evt) -> state.applyChannelOpenConfirmed(evt))
                .onEvent(ChannelWalletSagaEvent.UtxoMarkedSpentEvent.class,
                        (state, evt) -> state.applyUtxoMarkedSpent(evt))
                .onEvent(ChannelWalletSagaEvent.UtxoReleasedEvent.class,
                        (state, evt) -> state.applyUtxoReleased(evt))
                .build();
    }

    // --- Inner class with ActorContext access ---

    private static class ChannelWalletSagaWithContext extends ChannelWalletSaga {
        private final ActorContext<ChannelWalletSagaCommand> ctx;
        private ActorRef<WalletReply> replyAdapter;

        ChannelWalletSagaWithContext(PersistenceId persistenceId, ActorContext<ChannelWalletSagaCommand> ctx,
                                      ClusterSharding sharding, Duration walletAskTimeout) {
            super(persistenceId, sharding, walletAskTimeout);
            this.ctx = ctx;
            this.replyAdapter = ctx.messageAdapter(WalletReply.class,
                    reply -> new ChannelWalletSagaCommand.WrappedWalletReply(reply));
        }

        @Override
        protected ActorRef<WalletReply> getReplyAdapter() {
            return replyAdapter;
        }
    }
}
