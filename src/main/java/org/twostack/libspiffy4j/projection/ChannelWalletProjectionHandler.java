package org.twostack.libspiffy4j.projection;

import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.projection.eventsourced.EventEnvelope;
import org.apache.pekko.projection.jdbc.javadsl.JdbcHandler;
import org.twostack.libspiffy4j.aggregate.channel.ChannelEvent;
import org.twostack.libspiffy4j.saga.ChannelWalletSaga;
import org.twostack.libspiffy4j.saga.ChannelWalletSagaCommand;

/**
 * Projection handler that subscribes to channel aggregate events (tagged "channel")
 * and maps them to saga commands for UTXO lifecycle coordination.
 *
 * <p>Note: {@code InitiateFundingCommand} is called directly by the host app,
 * not via this projection.
 */
public final class ChannelWalletProjectionHandler
        extends JdbcHandler<EventEnvelope<ChannelEvent>, SpiffyJdbcSession> {

    private final ClusterSharding sharding;

    public ChannelWalletProjectionHandler(ClusterSharding sharding) {
        this.sharding = sharding;
    }

    @Override
    public void process(SpiffyJdbcSession session, EventEnvelope<ChannelEvent> envelope) {
        ChannelEvent event = envelope.event();

        switch (event) {
            case ChannelEvent.ChannelOpenedEvent e -> sagaRef(e.channelId())
                    .tell(new ChannelWalletSagaCommand.ConfirmChannelOpenedCommand(e.channelId()));

            case ChannelEvent.ChannelClosedEvent e -> sagaRef(e.channelId())
                    .tell(new ChannelWalletSagaCommand.HandleChannelClosedCommand(
                            e.channelId(), e.settlementTxId()));

            case ChannelEvent.ChannelRejectedEvent e -> sagaRef(e.channelId())
                    .tell(new ChannelWalletSagaCommand.HandleChannelFailedCommand(
                            e.channelId(), e.reason()));

            case ChannelEvent.RefundClaimedEvent e -> sagaRef(e.channelId())
                    .tell(new ChannelWalletSagaCommand.HandleRefundClaimedCommand(e.channelId()));

            // Other channel events don't affect the saga
            default -> {}
        }
    }

    private EntityRef<ChannelWalletSagaCommand> sagaRef(String channelId) {
        return sharding.entityRefFor(ChannelWalletSaga.ENTITY_TYPE_KEY, channelId);
    }
}
