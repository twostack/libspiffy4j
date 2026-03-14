package org.twostack.libspiffy4j.aggregate.wallet;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.javadsl.CommandHandlerBuilder;
import org.apache.pekko.persistence.typed.javadsl.EventHandler;
import org.apache.pekko.persistence.typed.javadsl.EventSourcedBehavior;
import org.apache.pekko.persistence.typed.javadsl.RetentionCriteria;
import org.twostack.libspiffy4j.model.UtxoStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class WalletAggregate
        extends EventSourcedBehavior<WalletCommand, WalletEvent, WalletState> {

    public static final EntityTypeKey<WalletCommand> ENTITY_TYPE_KEY =
            EntityTypeKey.create(WalletCommand.class, "WalletAggregate");

    public static Behavior<WalletCommand> create(PersistenceId persistenceId) {
        return new WalletAggregate(persistenceId);
    }

    private WalletAggregate(PersistenceId persistenceId) {
        super(persistenceId);
    }

    @Override
    public WalletState emptyState() {
        return new WalletState();
    }

    @Override
    public RetentionCriteria retentionCriteria() {
        return RetentionCriteria.snapshotEvery(100, 2);
    }

    @Override
    public Set<String> tagsFor(WalletEvent event) {
        return Set.of("wallet");
    }

    @Override
    public CommandHandler<WalletCommand, WalletEvent, WalletState> commandHandler() {
        CommandHandlerBuilder<WalletCommand, WalletEvent, WalletState> builder = newCommandHandlerBuilder();

        // Before wallet is created: only CreateWalletCommand is accepted
        builder.forState(state -> !state.isCreated())
                .onCommand(WalletCommand.CreateWalletCommand.class, this::onCreateWallet)
                .onCommand(WalletCommand.RecordAddressCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new WalletReply.Failure("Wallet not created")))
                .onCommand(WalletCommand.RecordUtxoCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new WalletReply.Failure("Wallet not created")))
                .onCommand(WalletCommand.RecordTransactionCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new WalletReply.Failure("Wallet not created")))
                .onCommand(WalletCommand.ReserveUtxoCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new WalletReply.Failure("Wallet not created")))
                .onCommand(WalletCommand.ReleaseUtxoCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new WalletReply.Failure("Wallet not created")))
                .onCommand(WalletCommand.MarkUtxoSpentCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new WalletReply.Failure("Wallet not created")))
                .onCommand(WalletCommand.UpdateConfirmationCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new WalletReply.Failure("Wallet not created")))
                .onCommand(WalletCommand.CleanupExpiredReservationsCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new WalletReply.Failure("Wallet not created")));

        // After wallet is created
        builder.forState(WalletState::isCreated)
                .onCommand(WalletCommand.CreateWalletCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new WalletReply.Failure("Wallet already exists")))
                .onCommand(WalletCommand.RecordAddressCommand.class, this::onRecordAddress)
                .onCommand(WalletCommand.RecordUtxoCommand.class, this::onRecordUtxo)
                .onCommand(WalletCommand.RecordTransactionCommand.class, this::onRecordTransaction)
                .onCommand(WalletCommand.ReserveUtxoCommand.class, this::onReserveUtxo)
                .onCommand(WalletCommand.ReleaseUtxoCommand.class, this::onReleaseUtxo)
                .onCommand(WalletCommand.MarkUtxoSpentCommand.class, this::onMarkUtxoSpent)
                .onCommand(WalletCommand.UpdateConfirmationCommand.class, this::onUpdateConfirmation)
                .onCommand(WalletCommand.CleanupExpiredReservationsCommand.class, this::onCleanupExpiredReservations);

        return builder.build();
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<WalletEvent, WalletState> onCreateWallet(
            WalletState state, WalletCommand.CreateWalletCommand cmd) {
        var event = new WalletEvent.WalletCreatedEvent(
                cmd.walletId(), cmd.name(), cmd.walletType(), cmd.networkType(),
                cmd.rootAddress(), cmd.metadata(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new WalletReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<WalletEvent, WalletState> onRecordAddress(
            WalletState state, WalletCommand.RecordAddressCommand cmd) {
        if (state.getKnownAddresses().contains(cmd.addressMetadata().address())) {
            return Effect().reply(cmd.replyTo(), new WalletReply.Failure("Address already recorded"));
        }
        int derivationIndex = cmd.addressMetadata().derivationIndex() != null
                ? cmd.addressMetadata().derivationIndex()
                : state.getNextDerivationIndex();
        var event = new WalletEvent.AddressRecordedEvent(
                cmd.walletId(), cmd.addressMetadata(), derivationIndex, Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new WalletReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<WalletEvent, WalletState> onRecordUtxo(
            WalletState state, WalletCommand.RecordUtxoCommand cmd) {
        if (state.getUtxoEntries().containsKey(cmd.utxo().key())) {
            return Effect().reply(cmd.replyTo(), new WalletReply.Failure("UTXO already recorded"));
        }
        var event = new WalletEvent.UtxoReceivedEvent(cmd.walletId(), cmd.utxo(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new WalletReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<WalletEvent, WalletState> onRecordTransaction(
            WalletState state, WalletCommand.RecordTransactionCommand cmd) {
        if (state.getKnownTxids().contains(cmd.transaction().txid())) {
            return Effect().reply(cmd.replyTo(), new WalletReply.Failure("Transaction already recorded"));
        }
        var event = new WalletEvent.TransactionRecordedEvent(cmd.walletId(), cmd.transaction(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new WalletReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<WalletEvent, WalletState> onReserveUtxo(
            WalletState state, WalletCommand.ReserveUtxoCommand cmd) {
        WalletState.UtxoEntry entry = state.getUtxoEntries().get(cmd.utxoKey());
        if (entry == null) {
            return Effect().reply(cmd.replyTo(), new WalletReply.Failure("UTXO not found"));
        }
        boolean effectivelyAvailable = entry.status() == UtxoStatus.AVAILABLE
                || (entry.status() == UtxoStatus.RESERVED
                    && entry.reservationExpiresAt() != null
                    && Instant.now().isAfter(entry.reservationExpiresAt()));
        if (!effectivelyAvailable) {
            return Effect().reply(cmd.replyTo(), new WalletReply.Failure("UTXO is not available"));
        }
        var event = new WalletEvent.UtxoReservedEvent(
                cmd.walletId(), cmd.utxoKey(), cmd.reservingTxId(),
                cmd.expiresAt(), cmd.priority(), cmd.reason(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new WalletReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<WalletEvent, WalletState> onReleaseUtxo(
            WalletState state, WalletCommand.ReleaseUtxoCommand cmd) {
        WalletState.UtxoEntry entry = state.getUtxoEntries().get(cmd.utxoKey());
        if (entry == null) {
            return Effect().reply(cmd.replyTo(), new WalletReply.Failure("UTXO not found"));
        }
        if (entry.status() != UtxoStatus.RESERVED) {
            return Effect().reply(cmd.replyTo(), new WalletReply.Failure("UTXO is not reserved"));
        }
        var event = new WalletEvent.UtxoReleasedEvent(cmd.walletId(), cmd.utxoKey(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new WalletReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<WalletEvent, WalletState> onMarkUtxoSpent(
            WalletState state, WalletCommand.MarkUtxoSpentCommand cmd) {
        WalletState.UtxoEntry entry = state.getUtxoEntries().get(cmd.utxoKey());
        if (entry == null) {
            return Effect().reply(cmd.replyTo(), new WalletReply.Failure("UTXO not found"));
        }
        if (entry.status() == UtxoStatus.SPENT) {
            return Effect().reply(cmd.replyTo(), new WalletReply.Failure("UTXO already spent"));
        }
        var event = new WalletEvent.UtxoSpentEvent(cmd.walletId(), cmd.utxoKey(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new WalletReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<WalletEvent, WalletState> onUpdateConfirmation(
            WalletState state, WalletCommand.UpdateConfirmationCommand cmd) {
        List<WalletEvent> events = new ArrayList<>();
        events.add(new WalletEvent.UtxoConfirmationUpdatedEvent(
                cmd.walletId(), cmd.txid(), cmd.confirmations(), cmd.blockHeight(), Instant.now()));
        if (state.getKnownTxids().contains(cmd.txid())) {
            events.add(new WalletEvent.TransactionConfirmedEvent(
                    cmd.walletId(), cmd.txid(), cmd.confirmations(), cmd.blockHeight(), Instant.now()));
        }
        return Effect().persist(events)
                .thenReply(cmd.replyTo(), s -> new WalletReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<WalletEvent, WalletState> onCleanupExpiredReservations(
            WalletState state, WalletCommand.CleanupExpiredReservationsCommand cmd) {
        List<WalletEvent> events = new ArrayList<>();
        Instant now = Instant.now();
        for (var mapEntry : state.getUtxoEntries().entrySet()) {
            WalletState.UtxoEntry entry = mapEntry.getValue();
            if (entry.status() == UtxoStatus.RESERVED
                    && entry.reservationExpiresAt() != null
                    && now.isAfter(entry.reservationExpiresAt())) {
                events.add(new WalletEvent.UtxoReleasedEvent(cmd.walletId(), mapEntry.getKey(), now));
            }
        }
        if (events.isEmpty()) {
            return Effect().reply(cmd.replyTo(), new WalletReply.Success(state));
        }
        return Effect().persist(events)
                .thenReply(cmd.replyTo(), s -> new WalletReply.Success(s));
    }

    @Override
    public EventHandler<WalletState, WalletEvent> eventHandler() {
        return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(WalletEvent.WalletCreatedEvent.class, (state, evt) -> state.applyWalletCreated(evt))
                .onEvent(WalletEvent.AddressRecordedEvent.class, (state, evt) -> state.applyAddressRecorded(evt))
                .onEvent(WalletEvent.UtxoReceivedEvent.class, (state, evt) -> state.applyUtxoReceived(evt))
                .onEvent(WalletEvent.UtxoSpentEvent.class, (state, evt) -> state.applyUtxoSpent(evt))
                .onEvent(WalletEvent.UtxoReservedEvent.class, (state, evt) -> state.applyUtxoReserved(evt))
                .onEvent(WalletEvent.UtxoReleasedEvent.class, (state, evt) -> state.applyUtxoReleased(evt))
                .onEvent(WalletEvent.UtxoConfirmationUpdatedEvent.class, (state, evt) -> state.applyUtxoConfirmationUpdated(evt))
                .onEvent(WalletEvent.TransactionRecordedEvent.class, (state, evt) -> state.applyTransactionRecorded(evt))
                .onEvent(WalletEvent.TransactionConfirmedEvent.class, (state, evt) -> state.applyTransactionConfirmed(evt))
                .onEvent(WalletEvent.WalletConfigurationUpdatedEvent.class, (state, evt) -> state)
                .build();
    }
}
