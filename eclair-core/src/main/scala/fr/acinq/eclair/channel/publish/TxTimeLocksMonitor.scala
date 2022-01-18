/*
 * Copyright 2021 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel.publish

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.{ByteVector32, Transaction}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchParentTxConfirmed, WatchParentTxConfirmedTriggered}
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishLogContext
import fr.acinq.eclair.transactions.Scripts

import scala.concurrent.duration.DurationLong
import scala.util.Random

/**
 * Created by t-bast on 10/06/2021.
 */

/**
 * This actor checks absolute and relative time locks for a given transaction.
 * Once all time locks have been satisfied, it notifies the requesting actor.
 */
object TxTimeLocksMonitor {

  case class TimeLocksOk()

  // @formatter:off
  sealed trait Command
  case class CheckTx(replyTo: ActorRef[TimeLocksOk], tx: Transaction, desc: String) extends Command
  private case class WrappedCurrentBlockCount(currentBlockCount: Long) extends Command
  private case object CheckRelativeTimeLock extends Command
  private case class ParentTxConfirmed(parentTxId: ByteVector32) extends Command
  // @formatter:on

  def apply(nodeParams: NodeParams, watcher: ActorRef[ZmqWatcher.Command], loggingInfo: TxPublishLogContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.withMdc(loggingInfo.mdc()) {
          Behaviors.receiveMessagePartial {
            case cmd: CheckTx => new TxTimeLocksMonitor(nodeParams, cmd, watcher, context, timers).checkAbsoluteTimeLock()
          }
        }
      }
    }
  }

}

private class TxTimeLocksMonitor(nodeParams: NodeParams,
                                 cmd: TxTimeLocksMonitor.CheckTx,
                                 watcher: ActorRef[ZmqWatcher.Command],
                                 context: ActorContext[TxTimeLocksMonitor.Command],
                                 timers: TimerScheduler[TxTimeLocksMonitor.Command]) {

  import TxTimeLocksMonitor._

  private val log = context.log

  def checkAbsoluteTimeLock(): Behavior[Command] = {
    val blockCount = nodeParams.currentBlockHeight
    val cltvTimeout = Scripts.cltvTimeout(cmd.tx)
    if (blockCount < cltvTimeout) {
      log.info("delaying publication of {} until block={} (current block={})", cmd.desc, cltvTimeout, blockCount)
      val messageAdapter = context.messageAdapter[CurrentBlockCount](cbc => WrappedCurrentBlockCount(cbc.blockCount))
      context.system.eventStream ! EventStream.Subscribe(messageAdapter)
      Behaviors.receiveMessagePartial {
        case WrappedCurrentBlockCount(currentBlockCount) =>
          if (cltvTimeout <= currentBlockCount) {
            context.system.eventStream ! EventStream.Unsubscribe(messageAdapter)
            timers.startSingleTimer(CheckRelativeTimeLock, (1 + Random.nextLong(nodeParams.maxTxPublishRetryDelay.toMillis)).millis)
            Behaviors.same
          } else {
            Behaviors.same
          }
        case CheckRelativeTimeLock => checkRelativeTimeLocks()
      }
    } else {
      checkRelativeTimeLocks()
    }
  }

  def checkRelativeTimeLocks(): Behavior[Command] = {
    val csvTimeouts = Scripts.csvTimeouts(cmd.tx)
    if (csvTimeouts.nonEmpty) {
      val watchConfirmedResponseMapper: ActorRef[WatchParentTxConfirmedTriggered] = context.messageAdapter(w => ParentTxConfirmed(w.tx.txid))
      csvTimeouts.foreach {
        case (parentTxId, csvTimeout) =>
          log.info("{} has a relative timeout of {} blocks, watching parentTxId={}", cmd.desc, csvTimeout, parentTxId)
          watcher ! WatchParentTxConfirmed(watchConfirmedResponseMapper, parentTxId, minDepth = csvTimeout)
      }
      waitForParentsToConfirm(csvTimeouts.keySet)
    } else {
      notifySender()
    }
  }

  def waitForParentsToConfirm(parentTxIds: Set[ByteVector32]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case ParentTxConfirmed(parentTxId) =>
        log.info("parent tx of {} has been confirmed (parent txid={})", cmd.desc, parentTxId)
        val remainingParentTxIds = parentTxIds - parentTxId
        if (remainingParentTxIds.isEmpty) {
          log.info("all parent txs of {} have been confirmed", cmd.desc)
          notifySender()
        } else {
          log.debug("some parent txs of {} are still unconfirmed (parent txids={})", cmd.desc, remainingParentTxIds.mkString(","))
          waitForParentsToConfirm(remainingParentTxIds)
        }
    }
  }

  def notifySender(): Behavior[Command] = {
    log.debug("time locks satisfied for {}", cmd.desc)
    cmd.replyTo ! TimeLocksOk()
    Behaviors.stopped
  }

}
