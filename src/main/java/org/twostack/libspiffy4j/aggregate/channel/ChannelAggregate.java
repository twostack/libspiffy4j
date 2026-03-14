package org.twostack.libspiffy4j.aggregate.channel;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.javadsl.CommandHandlerBuilder;
import org.apache.pekko.persistence.typed.javadsl.EventHandler;
import org.apache.pekko.persistence.typed.javadsl.EventSourcedBehavior;
import org.apache.pekko.persistence.typed.javadsl.RetentionCriteria;
import org.twostack.libspiffy4j.model.PaymentChannelRole;
import org.twostack.libspiffy4j.model.PaymentChannelState;

import java.time.Instant;
import java.util.Set;

public class ChannelAggregate
        extends EventSourcedBehavior<ChannelCommand, ChannelEvent, ChannelState> {

    public static final EntityTypeKey<ChannelCommand> ENTITY_TYPE_KEY =
            EntityTypeKey.create(ChannelCommand.class, "ChannelAggregate");

    public static Behavior<ChannelCommand> create(PersistenceId persistenceId) {
        return new ChannelAggregate(persistenceId);
    }

    private ChannelAggregate(PersistenceId persistenceId) {
        super(persistenceId);
    }

    @Override
    public ChannelState emptyState() {
        return new ChannelState();
    }

    @Override
    public RetentionCriteria retentionCriteria() {
        return RetentionCriteria.snapshotEvery(100, 2);
    }

    @Override
    public Set<String> tagsFor(ChannelEvent event) {
        return Set.of("channel");
    }

    @Override
    public CommandHandler<ChannelCommand, ChannelEvent, ChannelState> commandHandler() {
        CommandHandlerBuilder<ChannelCommand, ChannelEvent, ChannelState> builder = newCommandHandlerBuilder();

        // Before channel is created: only RequestChannel and AcceptChannel are accepted
        builder.forState(state -> !state.isCreated())
                .onCommand(ChannelCommand.RequestChannelCommand.class, this::onRequestChannel)
                .onCommand(ChannelCommand.AcceptChannelCommand.class, this::onAcceptChannel)
                .onCommand(ChannelCommand.RejectChannelCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new ChannelReply.Failure("Channel not created")))
                .onCommand(ChannelCommand.RecordServerAcceptanceCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new ChannelReply.Failure("Channel not created")))
                .onCommand(ChannelCommand.RequestRefundSignatureCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new ChannelReply.Failure("Channel not created")))
                .onCommand(ChannelCommand.ProvideRefundSignatureCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new ChannelReply.Failure("Channel not created")))
                .onCommand(ChannelCommand.OpenChannelCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new ChannelReply.Failure("Channel not created")))
                .onCommand(ChannelCommand.RecordPaymentCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new ChannelReply.Failure("Channel not created")))
                .onCommand(ChannelCommand.AcknowledgePaymentCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new ChannelReply.Failure("Channel not created")))
                .onCommand(ChannelCommand.CloseChannelCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new ChannelReply.Failure("Channel not created")))
                .onCommand(ChannelCommand.FinalizeCloseCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new ChannelReply.Failure("Channel not created")))
                .onCommand(ChannelCommand.ClaimRefundCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new ChannelReply.Failure("Channel not created")));

        // After channel is created
        builder.forState(ChannelState::isCreated)
                .onCommand(ChannelCommand.RequestChannelCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new ChannelReply.Failure("Channel already exists")))
                .onCommand(ChannelCommand.AcceptChannelCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new ChannelReply.Failure("Channel already exists")))
                .onCommand(ChannelCommand.RejectChannelCommand.class, this::onRejectChannel)
                .onCommand(ChannelCommand.RecordServerAcceptanceCommand.class, this::onRecordServerAcceptance)
                .onCommand(ChannelCommand.RequestRefundSignatureCommand.class, this::onRequestRefundSignature)
                .onCommand(ChannelCommand.ProvideRefundSignatureCommand.class, this::onProvideRefundSignature)
                .onCommand(ChannelCommand.OpenChannelCommand.class, this::onOpenChannel)
                .onCommand(ChannelCommand.RecordPaymentCommand.class, this::onRecordPayment)
                .onCommand(ChannelCommand.AcknowledgePaymentCommand.class, this::onAcknowledgePayment)
                .onCommand(ChannelCommand.CloseChannelCommand.class, this::onCloseChannel)
                .onCommand(ChannelCommand.FinalizeCloseCommand.class, this::onFinalizeClose)
                .onCommand(ChannelCommand.ClaimRefundCommand.class, this::onClaimRefund);

        return builder.build();
    }

    // --- Command handlers ---

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelEvent, ChannelState> onRequestChannel(
            ChannelState state, ChannelCommand.RequestChannelCommand cmd) {
        var event = new ChannelEvent.ChannelRequestedEvent(
                cmd.channelId(), cmd.walletId(), PaymentChannelRole.CLIENT,
                cmd.clientPeerId(), cmd.clientPubKeyHex(), cmd.clientAddressB58(),
                cmd.fundingAmountSats(), cmd.lockTimeUnix(), cmd.context(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new ChannelReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelEvent, ChannelState> onAcceptChannel(
            ChannelState state, ChannelCommand.AcceptChannelCommand cmd) {
        var event = new ChannelEvent.ChannelAcceptedEvent(
                cmd.channelId(), cmd.walletId(), PaymentChannelRole.SERVER,
                cmd.clientPeerId(), cmd.serverPeerId(),
                cmd.clientPubKeyHex(), cmd.serverPubKeyHex(),
                cmd.clientAddressB58(), cmd.serverAddressB58(),
                cmd.fundingAmountSats(), cmd.lockTimeUnix(), cmd.context(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new ChannelReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelEvent, ChannelState> onRejectChannel(
            ChannelState state, ChannelCommand.RejectChannelCommand cmd) {
        if (state.getChannelState() != PaymentChannelState.NEGOTIATING) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Can only reject channel in NEGOTIATING state, current: " + state.getChannelState()));
        }
        var event = new ChannelEvent.ChannelRejectedEvent(cmd.channelId(), cmd.reason(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new ChannelReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelEvent, ChannelState> onRecordServerAcceptance(
            ChannelState state, ChannelCommand.RecordServerAcceptanceCommand cmd) {
        if (state.getChannelState() != PaymentChannelState.NEGOTIATING) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Can only record server acceptance in NEGOTIATING state, current: " + state.getChannelState()));
        }
        var event = new ChannelEvent.ServerAcceptanceRecordedEvent(
                cmd.channelId(), cmd.serverPeerId(), cmd.serverPubKeyHex(),
                cmd.serverAddressB58(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new ChannelReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelEvent, ChannelState> onRequestRefundSignature(
            ChannelState state, ChannelCommand.RequestRefundSignatureCommand cmd) {
        if (state.getChannelState() != PaymentChannelState.FUNDING) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Can only build refund in FUNDING state, current: " + state.getChannelState()));
        }
        var event = new ChannelEvent.RefundBuiltEvent(
                cmd.channelId(), cmd.refundTxHex(), cmd.refundClientSigHex(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new ChannelReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelEvent, ChannelState> onProvideRefundSignature(
            ChannelState state, ChannelCommand.ProvideRefundSignatureCommand cmd) {
        if (state.getChannelState() != PaymentChannelState.FUNDING) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Can only countersign refund in FUNDING state, current: " + state.getChannelState()));
        }
        if (state.getRefundTxHex() == null) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Refund transaction not yet built"));
        }
        var event = new ChannelEvent.RefundCountersignedEvent(
                cmd.channelId(), cmd.refundServerSigHex(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new ChannelReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelEvent, ChannelState> onOpenChannel(
            ChannelState state, ChannelCommand.OpenChannelCommand cmd) {
        if (state.getChannelState() != PaymentChannelState.FUNDING) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Can only open channel in FUNDING state, current: " + state.getChannelState()));
        }
        // Guard: both refund signatures must be present
        if (state.getRefundClientSigHex() == null || state.getRefundServerSigHex() == null) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Both refund signatures must be present before opening channel"));
        }
        var event = new ChannelEvent.ChannelOpenedEvent(
                cmd.channelId(), cmd.fundingTxId(), cmd.fundingTxHex(),
                cmd.fundingOutputIndex(), cmd.fundingAncestorTxids(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new ChannelReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelEvent, ChannelState> onRecordPayment(
            ChannelState state, ChannelCommand.RecordPaymentCommand cmd) {
        if (state.getChannelState() != PaymentChannelState.OPEN) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Can only record payment in OPEN state, current: " + state.getChannelState()));
        }
        // Balance conservation invariant
        if (cmd.newClientBalanceSats() + cmd.newServerBalanceSats() != state.getFundingAmountSats()) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Balance conservation violated: client(" + cmd.newClientBalanceSats()
                            + ") + server(" + cmd.newServerBalanceSats() + ") != funding(" + state.getFundingAmountSats() + ")"));
        }
        // Sequence monotonicity
        if (cmd.sequenceNumber() <= state.getLatestSequenceNumber()) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Sequence number must be greater than current ("
                            + state.getLatestSequenceNumber() + "), got: " + cmd.sequenceNumber()));
        }
        var event = new ChannelEvent.PaymentRecordedEvent(
                cmd.channelId(), cmd.amountSats(), cmd.newClientBalanceSats(),
                cmd.newServerBalanceSats(), cmd.sequenceNumber(), cmd.paymentTxHex(),
                cmd.paymentTxId(), cmd.clientSignatureHex(), cmd.purpose(),
                cmd.invoiceId(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new ChannelReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelEvent, ChannelState> onAcknowledgePayment(
            ChannelState state, ChannelCommand.AcknowledgePaymentCommand cmd) {
        if (state.getChannelState() != PaymentChannelState.OPEN) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Can only acknowledge payment in OPEN state, current: " + state.getChannelState()));
        }
        var event = new ChannelEvent.PaymentAcknowledgedEvent(
                cmd.channelId(), cmd.fullySignedPaymentTxHex(), cmd.serverSignatureHex(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new ChannelReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelEvent, ChannelState> onCloseChannel(
            ChannelState state, ChannelCommand.CloseChannelCommand cmd) {
        if (state.getChannelState() != PaymentChannelState.OPEN) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Can only close channel in OPEN state, current: " + state.getChannelState()));
        }
        var event = new ChannelEvent.ChannelClosingEvent(cmd.channelId(), cmd.settlementTxHex(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new ChannelReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelEvent, ChannelState> onFinalizeClose(
            ChannelState state, ChannelCommand.FinalizeCloseCommand cmd) {
        if (state.getChannelState() != PaymentChannelState.CLOSING) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Can only finalize close in CLOSING state, current: " + state.getChannelState()));
        }
        var event = new ChannelEvent.ChannelClosedEvent(cmd.channelId(), cmd.settlementTxId(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new ChannelReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<ChannelEvent, ChannelState> onClaimRefund(
            ChannelState state, ChannelCommand.ClaimRefundCommand cmd) {
        if (state.getChannelState() != PaymentChannelState.OPEN
                && state.getChannelState() != PaymentChannelState.FUNDING) {
            return Effect().reply(cmd.replyTo(),
                    new ChannelReply.Failure("Can only claim refund in OPEN or FUNDING state, current: " + state.getChannelState()));
        }
        var event = new ChannelEvent.RefundClaimedEvent(cmd.channelId(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new ChannelReply.Success(s));
    }

    @Override
    public EventHandler<ChannelState, ChannelEvent> eventHandler() {
        return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(ChannelEvent.ChannelRequestedEvent.class, (state, evt) -> state.applyChannelRequested(evt))
                .onEvent(ChannelEvent.ChannelAcceptedEvent.class, (state, evt) -> state.applyChannelAccepted(evt))
                .onEvent(ChannelEvent.ChannelRejectedEvent.class, (state, evt) -> state.applyChannelRejected(evt))
                .onEvent(ChannelEvent.ServerAcceptanceRecordedEvent.class, (state, evt) -> state.applyServerAcceptanceRecorded(evt))
                .onEvent(ChannelEvent.RefundBuiltEvent.class, (state, evt) -> state.applyRefundBuilt(evt))
                .onEvent(ChannelEvent.RefundCountersignedEvent.class, (state, evt) -> state.applyRefundCountersigned(evt))
                .onEvent(ChannelEvent.ChannelOpenedEvent.class, (state, evt) -> state.applyChannelOpened(evt))
                .onEvent(ChannelEvent.PaymentRecordedEvent.class, (state, evt) -> state.applyPaymentRecorded(evt))
                .onEvent(ChannelEvent.PaymentAcknowledgedEvent.class, (state, evt) -> state.applyPaymentAcknowledged(evt))
                .onEvent(ChannelEvent.ChannelClosingEvent.class, (state, evt) -> state.applyChannelClosing(evt))
                .onEvent(ChannelEvent.ChannelClosedEvent.class, (state, evt) -> state.applyChannelClosed(evt))
                .onEvent(ChannelEvent.RefundClaimedEvent.class, (state, evt) -> state.applyRefundClaimed(evt))
                .build();
    }
}
