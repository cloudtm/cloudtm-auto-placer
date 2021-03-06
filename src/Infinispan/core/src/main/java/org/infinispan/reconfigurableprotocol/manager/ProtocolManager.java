package org.infinispan.reconfigurableprotocol.manager;

import org.infinispan.commands.write.WriteCommand;
import org.infinispan.reconfigurableprotocol.ReconfigurableProtocol;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import javax.transaction.Transaction;

/**
 * Manages the current replication protocol in use and synchronize it with the epoch number
 *
 * @author Pedro Ruivo
 * @since 5.2
 */
public class ProtocolManager {

   private static final Log log = LogFactory.getLog(ProtocolManager.class);
   private final StatisticManager statisticManager;
   private long epoch = 0;
   private ReconfigurableProtocol current;
   private ReconfigurableProtocol old;
   private State state;
   private StatisticManager.Stats currentStat;

   public ProtocolManager(StatisticManager statisticManager) {
      this.statisticManager = statisticManager;
   }

   /**
    * init the protocol manager with the initial replication protocol
    *
    * @param actual the initial replication protocol
    * @param epoch  the initial epoch
    */
   public final synchronized void init(ReconfigurableProtocol actual, long epoch) {
      this.old = null;
      this.current = actual;
      this.state = State.SAFE;
      this.epoch = epoch;
   }

   /**
    * returns the current replication protocol
    *
    * @return the current replication protocol
    */
   public final synchronized ReconfigurableProtocol getCurrent() {
      return current;
   }

   /**
    * signal the switch start changing the state to "in progress"
    */
   public final synchronized void inProgress() {
      this.currentStat = statisticManager.createNewStats(current.getUniqueProtocolName());
      this.state = State.IN_PROGRESS;
      if (log.isDebugEnabled()) {
         log.debugf("Changed state to %s", state);
      }
   }

   /**
    * atomically changes the current protocol and increments the epoch
    *
    * @param newProtocol the new replication protocol to use
    */
   public final synchronized void change(ReconfigurableProtocol newProtocol, boolean safe) {
      if (safe) {
         currentStat.safe(newProtocol == null ? null : newProtocol.getUniqueProtocolName());
         state = State.SAFE;
      } else {
         currentStat.unsafe(newProtocol == null ? null : newProtocol.getUniqueProtocolName());
         state = State.UNSAFE;
      }
      notifyAll();
      if (newProtocol == null || isCurrentProtocol(newProtocol)) {
         if (log.isDebugEnabled()) {
            log.debugf("Changed state to %s", state);
         }
         return;
      }
      old = current;
      current = newProtocol;
      epoch++;
      this.notifyAll();
      if (log.isDebugEnabled()) {
         log.debugf("Changed to new protocol. Current protocol is %s, current epoch is %s and current state is %s",
                    current.getUniqueProtocolName(), epoch, state);
      }
   }

   /**
    * check if the {@param reconfigurableProtocol} is the current replication protocol in use
    *
    * @param reconfigurableProtocol the replication protocol to check
    * @return true if it is the current replication protocol, false otherwise
    */
   public final synchronized boolean isCurrentProtocol(ReconfigurableProtocol reconfigurableProtocol) {
      return reconfigurableProtocol == current || reconfigurableProtocol.equals(current);
   }

   /**
    * atomically returns the current replication protocol and epoch
    *
    * @return the current replication protocol and epoch
    */
   public final synchronized CurrentProtocolInfo getCurrentProtocolInfo() {
      return new CurrentProtocolInfo(epoch, current, old, state);
   }

   /**
    * returns when the current epoch is higher or equals than {@param epoch}, blocking until that condition is true
    *
    * @param epoch the epoch to be ensured
    * @throws InterruptedException if it is interrupted while waiting
    */
   public final synchronized void ensure(long epoch) throws InterruptedException {
      if (log.isDebugEnabled()) {
         log.debugf("[%s] will block until %s >= %s", Thread.currentThread().getName(), this.epoch, epoch);
      }
      while (this.epoch < epoch) {
         this.wait();
      }
      if (log.isDebugEnabled()) {
         log.debugf("[%s] epoch is the desired. Moving on...", Thread.currentThread().getName());
      }
   }

   /**
    * returns true if the current state is unsafe
    *
    * @return true if the current state is unsafe
    */
   public final synchronized boolean isUnsafe() {
      return state == State.UNSAFE;
   }

   /**
    * returns true if the current state is switch in progress
    *
    * @return true if the current state is switch in progress
    */
   public final synchronized boolean isInProgress() {
      return state == State.IN_PROGRESS;
   }

   /**
    * ensure that the switch is not in progress when the method returns
    *
    * @throws InterruptedException if interrupted while waiting for the switch to end
    */
   public final synchronized void ensureNotInProgress() throws InterruptedException {
      if (log.isDebugEnabled()) {
         log.debugf("[%s] will wait until no switch is in progress", Thread.currentThread().getName());
      }
      while (isInProgress()) {
         wait();
      }
      if (log.isDebugEnabled()) {
         log.debugf("[%s] no switch in progress. Moving on...", Thread.currentThread().getName());
      }
   }

   public final synchronized CurrentProtocolInfo startCommitTransaction(GlobalTransaction globalTransaction, WriteCommand[] writeSet,
                                                                        ReconfigurableProtocol execProtocol, Transaction transaction) {
      if (isCurrentProtocol(execProtocol)) {
         execProtocol.commitTransaction(globalTransaction, writeSet, transaction);
      } else {
         execProtocol.commitTransaction(transaction);
         current.addLocalTransaction(globalTransaction, writeSet);
      }
      return getCurrentProtocolInfo();
   }

   public final synchronized void addNumberOfTransactionsAborted(int val) {
      if (currentStat != null) {
         currentStat.addNumberOfTransactionAborted(val);
      }
   }

   public final synchronized long getEpoch() {
      return epoch;
   }

   /**
    * the possible states
    */
   private static enum State {
      SAFE,
      UNSAFE,
      IN_PROGRESS
   }

   /**
    * class used to atomically retrieve the current replication protocol and epoch
    */
   public static class CurrentProtocolInfo {
      private final long epoch;
      private final ReconfigurableProtocol current, old;
      private final State state;

      public CurrentProtocolInfo(long epoch, ReconfigurableProtocol current, ReconfigurableProtocol old, State state) {
         this.epoch = epoch;
         this.current = current;
         this.old = old;
         this.state = state;
      }

      public final long getEpoch() {
         return epoch;
      }

      public final ReconfigurableProtocol getCurrent() {
         return current;
      }

      public final ReconfigurableProtocol getOld() {
         return old;
      }

      public final boolean isUnsafe() {
         return state == State.UNSAFE;
      }

      public final String printState() {
         return state.name();
      }

      @Override
      public final String toString() {
         return "CurrentProtocolInfo{" +
               "epoch=" + epoch +
               ", current=" + current +
               ", old=" + old +
               ", state=" + state +
               '}';
      }
   }
}
