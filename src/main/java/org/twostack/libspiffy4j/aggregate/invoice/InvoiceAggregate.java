package org.twostack.libspiffy4j.aggregate.invoice;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.javadsl.CommandHandlerBuilder;
import org.apache.pekko.persistence.typed.javadsl.EventHandler;
import org.apache.pekko.persistence.typed.javadsl.EventSourcedBehavior;
import org.apache.pekko.persistence.typed.javadsl.RetentionCriteria;
import org.twostack.libspiffy4j.model.InvoiceStatus;

import java.time.Instant;
import java.util.Set;

public class InvoiceAggregate
        extends EventSourcedBehavior<InvoiceCommand, InvoiceEvent, InvoiceState> {

    public static final EntityTypeKey<InvoiceCommand> ENTITY_TYPE_KEY =
            EntityTypeKey.create(InvoiceCommand.class, "InvoiceAggregate");

    public static Behavior<InvoiceCommand> create(PersistenceId persistenceId) {
        return new InvoiceAggregate(persistenceId);
    }

    private InvoiceAggregate(PersistenceId persistenceId) {
        super(persistenceId);
    }

    @Override
    public InvoiceState emptyState() {
        return new InvoiceState();
    }

    @Override
    public RetentionCriteria retentionCriteria() {
        return RetentionCriteria.snapshotEvery(100, 2);
    }

    @Override
    public Set<String> tagsFor(InvoiceEvent event) {
        return Set.of("invoice");
    }

    @Override
    public CommandHandler<InvoiceCommand, InvoiceEvent, InvoiceState> commandHandler() {
        CommandHandlerBuilder<InvoiceCommand, InvoiceEvent, InvoiceState> builder = newCommandHandlerBuilder();

        // Before invoice is created: only CreateInvoiceCommand is accepted
        builder.forState(state -> !state.isCreated())
                .onCommand(InvoiceCommand.CreateInvoiceCommand.class, this::onCreateInvoice)
                .onCommand(InvoiceCommand.MarkInvoicePaidCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new InvoiceReply.Failure("Invoice not created")))
                .onCommand(InvoiceCommand.CancelInvoiceCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new InvoiceReply.Failure("Invoice not created")))
                .onCommand(InvoiceCommand.ExpireInvoiceCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new InvoiceReply.Failure("Invoice not created")));

        // After invoice is created
        builder.forState(InvoiceState::isCreated)
                .onCommand(InvoiceCommand.CreateInvoiceCommand.class, (state, cmd) ->
                        Effect().reply(cmd.replyTo(), new InvoiceReply.Failure("Invoice already exists")))
                .onCommand(InvoiceCommand.MarkInvoicePaidCommand.class, this::onMarkInvoicePaid)
                .onCommand(InvoiceCommand.CancelInvoiceCommand.class, this::onCancelInvoice)
                .onCommand(InvoiceCommand.ExpireInvoiceCommand.class, this::onExpireInvoice);

        return builder.build();
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<InvoiceEvent, InvoiceState> onCreateInvoice(
            InvoiceState state, InvoiceCommand.CreateInvoiceCommand cmd) {
        var event = new InvoiceEvent.InvoiceCreatedEvent(
                cmd.invoiceId(), cmd.walletId(), cmd.addresses(), cmd.amountSats(),
                cmd.outputs(), cmd.description(), cmd.expiresAt(), cmd.metadata(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new InvoiceReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<InvoiceEvent, InvoiceState> onMarkInvoicePaid(
            InvoiceState state, InvoiceCommand.MarkInvoicePaidCommand cmd) {
        if (state.getStatus() != InvoiceStatus.PENDING) {
            return Effect().reply(cmd.replyTo(),
                    new InvoiceReply.Failure("Cannot pay invoice with status " + state.getStatus()));
        }
        if (cmd.amountReceivedSats() < state.getAmountSats()) {
            return Effect().reply(cmd.replyTo(),
                    new InvoiceReply.Failure("Insufficient payment: received " + cmd.amountReceivedSats()
                            + " but required " + state.getAmountSats()));
        }
        if (!state.getAddressSet().contains(cmd.paymentAddress())) {
            return Effect().reply(cmd.replyTo(),
                    new InvoiceReply.Failure("Payment address not associated with this invoice"));
        }
        var event = new InvoiceEvent.InvoicePaidEvent(
                cmd.invoiceId(), cmd.paymentTxid(), cmd.amountReceivedSats(),
                cmd.paymentAddress(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new InvoiceReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<InvoiceEvent, InvoiceState> onCancelInvoice(
            InvoiceState state, InvoiceCommand.CancelInvoiceCommand cmd) {
        if (state.getStatus() == InvoiceStatus.PAID) {
            return Effect().reply(cmd.replyTo(),
                    new InvoiceReply.Failure("Cannot cancel a paid invoice"));
        }
        if (state.getStatus() == InvoiceStatus.CANCELLED) {
            return Effect().reply(cmd.replyTo(),
                    new InvoiceReply.Failure("Invoice already cancelled"));
        }
        var event = new InvoiceEvent.InvoiceCancelledEvent(
                cmd.invoiceId(), cmd.reason(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new InvoiceReply.Success(s));
    }

    private org.apache.pekko.persistence.typed.javadsl.Effect<InvoiceEvent, InvoiceState> onExpireInvoice(
            InvoiceState state, InvoiceCommand.ExpireInvoiceCommand cmd) {
        if (state.getStatus() != InvoiceStatus.PENDING) {
            return Effect().reply(cmd.replyTo(),
                    new InvoiceReply.Failure("Only PENDING invoices can expire"));
        }
        var event = new InvoiceEvent.InvoiceExpiredEvent(cmd.invoiceId(), Instant.now());
        return Effect().persist(event)
                .thenReply(cmd.replyTo(), s -> new InvoiceReply.Success(s));
    }

    @Override
    public EventHandler<InvoiceState, InvoiceEvent> eventHandler() {
        return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(InvoiceEvent.InvoiceCreatedEvent.class, (state, evt) -> state.applyInvoiceCreated(evt))
                .onEvent(InvoiceEvent.InvoicePaidEvent.class, (state, evt) -> state.applyInvoicePaid(evt))
                .onEvent(InvoiceEvent.InvoiceExpiredEvent.class, (state, evt) -> state.applyInvoiceExpired(evt))
                .onEvent(InvoiceEvent.InvoiceCancelledEvent.class, (state, evt) -> state.applyInvoiceCancelled(evt))
                .build();
    }
}
