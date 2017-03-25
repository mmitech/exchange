/*
 * This file is part of bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bisq.core.trade.protocol;


import io.bisq.common.handlers.ErrorMessageHandler;
import io.bisq.common.handlers.ResultHandler;
import io.bisq.core.trade.BuyerAsTakerTrade;
import io.bisq.core.trade.Trade;
import io.bisq.core.trade.protocol.tasks.buyer.BuyerSendFiatTransferStartedMessage;
import io.bisq.core.trade.protocol.tasks.buyer_as_taker.*;
import io.bisq.core.trade.protocol.tasks.taker.*;
import io.bisq.protobuffer.message.Message;
import io.bisq.protobuffer.message.p2p.MailboxMessage;
import io.bisq.protobuffer.message.trade.FinalizePayoutTxRequest;
import io.bisq.protobuffer.message.trade.PublishDepositTxRequest;
import io.bisq.protobuffer.message.trade.TradeMessage;
import io.bisq.protobuffer.payload.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BuyerAsTakerProtocol extends TradeProtocol implements BuyerProtocol, TakerProtocol {
    private final BuyerAsTakerTrade buyerAsTakerTrade;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerAsTakerProtocol(BuyerAsTakerTrade trade) {
        super(trade);

        this.buyerAsTakerTrade = trade;

        processModel.tradingPeer.setPubKeyRing(trade.getOffer().getPubKeyRing());

        // If we are after the timeLock state we need to setup the listener again
        Trade.State tradeState = trade.getState();
        Trade.Phase phase = tradeState.getPhase();
        if (trade.getPayoutTx() != null && (phase == Trade.Phase.FIAT_RECEIVED || phase == Trade.Phase.PAYOUT_PAID) &&
                tradeState != Trade.State.PAYOUT_BROAD_CASTED) {
            TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
                    () -> {
                        handleTaskRunnerSuccess("SetupPayoutTxLockTimeReachedListener");
                        processModel.onComplete();
                    },
                    this::handleTaskRunnerFault);

            taskRunner.addTasks(BuyerAsTakerBroadcastPayoutTx.class);  //TODO: locktime
            taskRunner.run();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Mailbox
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void doApplyMailboxMessage(Message message, Trade trade) {
        this.trade = trade;
        final NodeAddress senderNodeAddress = ((MailboxMessage) message).getSenderNodeAddress();
        if (message instanceof PublishDepositTxRequest)
            handle((PublishDepositTxRequest) message, senderNodeAddress);
        else if (message instanceof FinalizePayoutTxRequest) {
            handle((FinalizePayoutTxRequest) message, senderNodeAddress);
        } else
            log.error("We received an unhandled MailboxMessage" + message.toString());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Start trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void takeAvailableOffer() {
        TradeTaskRunner taskRunner = new TradeTaskRunner(buyerAsTakerTrade,
                () -> handleTaskRunnerSuccess("takeAvailableOffer"),
                this::handleTaskRunnerFault);

        taskRunner.addTasks(
                TakerSelectArbitrator.class,
                TakerVerifyMakerAccount.class,
                TakerVerifyMakerFeePayment.class,
                TakerCreateTakerFeeTx.class,
                TakerBroadcastTakerFeeTx.class,
                BuyerAsTakerCreatesDepositTxInputs.class,
                TakerSendPayDepositRequest.class
        );
        startTimeout();
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(PublishDepositTxRequest tradeMessage, NodeAddress sender) {
        stopTimeout();
        processModel.setTradeMessage(tradeMessage);
        processModel.setTempTradingPeerNodeAddress(sender);

        TradeTaskRunner taskRunner = new TradeTaskRunner(buyerAsTakerTrade,
                () -> handleTaskRunnerSuccess("PublishDepositTxRequest"),
                this::handleTaskRunnerFault);
        taskRunner.addTasks(
                TakerProcessPublishDepositTxRequest.class,
                TakerVerifyMakerAccount.class,
                TakerVerifyMakerFeePayment.class,
                TakerVerifyAndSignContract.class,
                BuyerAsTakerSignAndPublishDepositTx.class,
                TakerSendDepositTxPublishedMessage.class,
                TakerPublishTradeStatistics.class
        );
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from UI
    ///////////////////////////////////////////////////////////////////////////////////////////

    // User clicked the "bank transfer started" button
    @Override
    public void onFiatPaymentStarted(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        if (buyerAsTakerTrade.getState().ordinal() <= Trade.State.BUYER_SENT_FIAT_PAYMENT_INITIATED_MSG.ordinal()) {
            if (buyerAsTakerTrade.getState() == Trade.State.BUYER_SENT_FIAT_PAYMENT_INITIATED_MSG)
                log.warn("onFiatPaymentStarted called twice. " +
                        "That is expected if the app starts up and the other peer has still not continued.");

            buyerAsTakerTrade.setState(Trade.State.BUYER_CONFIRMED_FIAT_PAYMENT_INITIATED);

            TradeTaskRunner taskRunner = new TradeTaskRunner(buyerAsTakerTrade,
                    () -> {
                        resultHandler.handleResult();
                        handleTaskRunnerSuccess("onFiatPaymentStarted");
                    },
                    (errorMessage) -> {
                        errorMessageHandler.handleErrorMessage(errorMessage);
                        handleTaskRunnerFault(errorMessage);
                    });
            taskRunner.addTasks(
                    TakerVerifyMakerAccount.class,
                    TakerVerifyMakerFeePayment.class,
                    BuyerSendFiatTransferStartedMessage.class
            );
            taskRunner.run();
        } else {
            log.warn("onFiatPaymentStarted called twice. " +
                    "That should not happen.\n" +
                    "state=" + buyerAsTakerTrade.getState());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(FinalizePayoutTxRequest tradeMessage, NodeAddress sender) {
        processModel.setTradeMessage(tradeMessage);
        processModel.setTempTradingPeerNodeAddress(sender);

        TradeTaskRunner taskRunner = new TradeTaskRunner(buyerAsTakerTrade,
                () -> {
                    handleTaskRunnerSuccess("FinalizePayoutTxRequest");
                    processModel.onComplete();
                },
                this::handleTaskRunnerFault);

        taskRunner.addTasks(
                BuyerAsTaker___ProcessFinalizePayoutTxRequest.class,  //TODO: locktime
                BuyerAsTakerSignAndFinalizePayoutTx.class,  //TODO: locktime
                BuyerAsTaker___SendPayoutTxFinalizedMessage.class,
                BuyerAsTakerBroadcastPayoutTx.class  //TODO: locktime
        );
        taskRunner.run();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Massage dispatcher
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void doHandleDecryptedMessage(TradeMessage tradeMessage, NodeAddress sender) {
        if (tradeMessage instanceof PublishDepositTxRequest) {
            handle((PublishDepositTxRequest) tradeMessage, sender);
        } else if (tradeMessage instanceof FinalizePayoutTxRequest) {
            handle((FinalizePayoutTxRequest) tradeMessage, sender);
        } else {
            log.error("Incoming message not supported. " + tradeMessage);
        }
    }
}