package org.infinispan.reconfigurableprotocol;

import org.infinispan.CacheException;
import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.remote.ReconfigurableProtocolCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.container.DataContainer;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.interceptors.*;
import org.infinispan.interceptors.base.CommandInterceptor;
import org.infinispan.interceptors.gmu.GMUDistributionInterceptor;
import org.infinispan.interceptors.gmu.GMUEntryWrappingInterceptor;
import org.infinispan.interceptors.gmu.GMUReplicationInterceptor;
import org.infinispan.interceptors.locking.NonTransactionalLockingInterceptor;
import org.infinispan.interceptors.locking.OptimisticLockingInterceptor;
import org.infinispan.interceptors.locking.OptimisticReadWriteLockingInterceptor;
import org.infinispan.interceptors.locking.PessimisticLockingInterceptor;
import org.infinispan.reconfigurableprotocol.manager.ReconfigurableReplicationManager;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.transaction.LocalTransaction;
import org.infinispan.transaction.LockingMode;
import org.infinispan.transaction.TransactionMode;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.util.Util;
import org.infinispan.util.concurrent.IsolationLevel;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.infinispan.commands.remote.ReconfigurableProtocolCommand.Type;
import static org.infinispan.interceptors.InterceptorChain.InterceptorType;

/**
 * represents an instance of a replication protocol
 *
 * @author Pedro Ruivo
 * @since 5.2
 */
public abstract class ReconfigurableProtocol {

   protected final Log log = LogFactory.getLog(getClass());

   private static final String LOCAL_STOP_ACK = "_LOCAL_ACK_";

   protected Configuration configuration;
   private ComponentRegistry componentRegistry;
   private RpcManager rpcManager;
   private CommandsFactory commandsFactory;
   private DataContainer dataContainer;
   protected ReconfigurableReplicationManager manager;

   protected final Map<GlobalTransaction, Set<Object>> localTransactions;
   protected final Map<GlobalTransaction, Set<Object>> remoteTransactions;
   protected final Set<Transaction> localExecutionTransactions;

   private final AckCollector ackCollector;

   public ReconfigurableProtocol() {
      localTransactions = new HashMap<GlobalTransaction, Set<Object>>();
      remoteTransactions = new HashMap<GlobalTransaction, Set<Object>>();
      localExecutionTransactions = new HashSet<Transaction>();
      ackCollector = new AckCollector();
   }

   //sets the dependencies
   public final void initialize(Configuration configuration, ComponentRegistry componentRegistry,
                                ReconfigurableReplicationManager manager) {
      this.configuration = configuration;
      this.componentRegistry = componentRegistry;
      this.manager = manager;
      this.rpcManager = getComponent(RpcManager.class);
      this.commandsFactory = getComponent(CommandsFactory.class);
      this.dataContainer = getComponent(DataContainer.class);
   }

   public final void startTransaction(Transaction transaction) {
      synchronized (localExecutionTransactions) {
         localExecutionTransactions.add(transaction);
      }
   }

   public final void commitTransaction(GlobalTransaction globalTransaction, WriteCommand[] writeSet,
                                       Transaction transaction) {
      synchronized (localExecutionTransactions) {
         synchronized (localTransactions) {
            localExecutionTransactions.remove(transaction);
            localExecutionTransactions.notifyAll();
            try {
               if (transaction.getStatus() == Status.STATUS_MARKED_ROLLBACK) {
                  throw new CacheException("Transaction " + globalTransaction.prettyPrint() + " is marked for rollback.");
               }
            } catch (SystemException e) {
               throw new CacheException(e);
            }
            localTransactions.put(globalTransaction, Util.getAffectedKeys(Arrays.asList(writeSet), dataContainer));
         }
      }
   }

   public final void commitTransaction(Transaction transaction) {
      synchronized (localExecutionTransactions) {
         localExecutionTransactions.remove(transaction);
         localExecutionTransactions.notifyAll();
         try {
            if (transaction.getStatus() == Status.STATUS_MARKED_ROLLBACK) {
               throw new CacheException("Transaction " + transaction + " is marked for rollback.");
            }
         } catch (SystemException e) {
            throw new CacheException(e);
         }
      }
   }

   /**
    * Adds the global transaction to the list of local transactions finished by this protocol
    *
    * @param globalTransaction   the global transaction
    * @param writeSet            the modifications array
    */
   public final void addLocalTransaction(GlobalTransaction globalTransaction, WriteCommand[] writeSet) {
      synchronized (localTransactions) {
         localTransactions.put(globalTransaction, Util.getAffectedKeys(Arrays.asList(writeSet), dataContainer));
      }
   }

   /**
    * Removes the global transaction to the list of local transactions finished by this protocol
    *
    * @param globalTransaction   the global transaction
    */
   public final void removeLocalTransaction(GlobalTransaction globalTransaction) {
      synchronized (localTransactions) {
         localTransactions.remove(globalTransaction);
         localTransactions.notifyAll();
      }
   }

   /**
    * Adds the global transaction to the list of remote transactions finished by this protocol
    *
    * @param globalTransaction   the global transaction
    * @param writeSet            the modifications array
    */
   public final void addRemoteTransaction(GlobalTransaction globalTransaction, WriteCommand[] writeSet) {
      synchronized (remoteTransactions) {
         if (remoteTransactions.get(globalTransaction) != null) {
            //no-op
         } else if (writeSet == null) {
            remoteTransactions.put(globalTransaction, null);
         } else {
            remoteTransactions.put(globalTransaction, Util.getAffectedKeys(Arrays.asList(writeSet), dataContainer));
         }
      }
   }

   /**
    * Removes the global transaction to the list of remote transactions finished by this protocol
    *
    * @param globalTransaction   the global transaction
    */
   public final void removeRemoteTransaction(GlobalTransaction globalTransaction) {
      synchronized (remoteTransactions) {
         remoteTransactions.remove(globalTransaction);
         remoteTransactions.notifyAll();
      }
   }

   /**
    * method invoked when a message is received for this protocol
    *
    * @param data the data in the message
    * @param from the sender
    */
   public final void handleData(Object data, Address from) {
      if (LOCAL_STOP_ACK.equals(data)) {
         if (log.isTraceEnabled()) {
            log.tracef("Data message from %s received and it is a stop ack", from);
         }
         ackCollector.ack(from);
      } else {
         if (log.isTraceEnabled()) {
            log.tracef("Data message from %s received. Data is %s", from, data);
         }
         internalHandleData(data, from);
      }
   }

   @Override
   public final String toString() {
      return "ReconfigurableProtocol{protocolName=" + getUniqueProtocolName() + "}";
   }

   /**
    * it ensures that the write set in parameters does not conflict with transactions that are committing with this
    * protocol. This method can block waiting for transactions to finish
    *
    * @param writeSet               the modifications array
    * @throws InterruptedException  if it is interrupted while waiting
    */
   public final void ensureNoConflict(WriteCommand[] writeSet) throws InterruptedException {
      if (writeSet == null) {
         return;
      }

      Set<Object> keys = Util.getAffectedKeys(Arrays.asList(writeSet), dataContainer);
      synchronized (localTransactions) {
         boolean conflict = true;
         while (conflict) {
            conflict = false;
            for (Set<Object> localWriteSet : localTransactions.values()) {
               if (localWriteSet == null || keys == null) {
                  conflict = true;
                  localTransactions.wait();
                  break;
               }
            }
         }
      }

      synchronized (remoteTransactions) {
         boolean conflict = true;
         while (conflict) {
            conflict = false;
            for (Set<Object> localWriteSet : remoteTransactions.values()) {
               if (localWriteSet == null || keys == null) {
                  conflict = true;
                  remoteTransactions.wait();
                  break;
               }
            }
         }
      }
   }

   /**
    * returns all the pending local transactions and their write set
    *
    * @return  all the pending local transactions and their write set
    */
   public final String printLocalTransactions() {
      StringBuilder sb = new StringBuilder("Local committing transactions are:\n");
      synchronized (localTransactions) {
         for (Map.Entry<GlobalTransaction, Set<Object>> entry : localTransactions.entrySet()) {
            sb.append(entry.getKey().prettyPrint()).append("=>").append(entry.getValue()).append("\n");
         }
      }

      sb.append("\nLocal executing transactions are:\n");
      synchronized (localExecutionTransactions) {
         for (Transaction transaction : localExecutionTransactions) {
            sb.append(transaction).append("\n");
         }
      }

      return sb.toString();
   }

   /**
    * returns all the pending remote transactions and their write set
    *
    * @return  all the pending remote transactions and their write set
    */
   public final String printRemoteTransactions() {
      StringBuilder sb = new StringBuilder("Remote transactions are:\n");
      synchronized (remoteTransactions) {
         for (Map.Entry<GlobalTransaction, Set<Object>> entry : remoteTransactions.entrySet()) {
            sb.append(entry.getKey().prettyPrint()).append("=>").append(entry.getValue()).append("\n");
         }
      }
      return sb.toString();
   }

   /**
    * blocks until the local transaction set is empty, i.e., no more local transactions are committing
    *
    * @throws InterruptedException  if interrupted
    */
   protected final void awaitUntilLocalCommittingTransactionsFinished() throws InterruptedException {
      if (log.isTraceEnabled()) {
         log.tracef("[%s] thread will wait until all local transaction are finished", Thread.currentThread().getName());
      }
      synchronized (localTransactions) {
         while (!localTransactions.isEmpty()) {
            localTransactions.wait();
         }
      }
      if (log.isTraceEnabled()) {
         log.tracef("[%s] all local transaction are finished. Moving on...", Thread.currentThread().getName());
      }
   }

   protected final void awaitUntilLocalExecutingTransactionsFinished(boolean abort) throws InterruptedException {
      if (log.isTraceEnabled()) {
         log.tracef("[%s] thread will wait until all executing local transaction are finished", Thread.currentThread().getName());
      }
      synchronized (localExecutionTransactions) {
         if (abort) {
            for (Transaction transaction : localExecutionTransactions) {
               try {
                  transaction.setRollbackOnly();
               } catch (SystemException e) {
                  if (log.isDebugEnabled()) {
                     log.warnf(e, "Error marking transaction %s to rollback only.", transaction);
                  } else {
                     log.warnf("Error marking transaction %s to rollback only. %s", transaction, e.getMessage());
                  }
               }
            }
            manager.addNumberOfAbortedTransactionDueToSwitch(localExecutionTransactions.size());
         } else {
            while (!localExecutionTransactions.isEmpty()) {
               localExecutionTransactions.wait();
            }
         }
      }
      if (log.isTraceEnabled()) {
         log.tracef("[%s] all local transaction are finished. Moving on...", Thread.currentThread().getName());
      }
   }

   /**
    * blocks until the remote transaction set is empty, i.e., no more remote transactions are committing
    *
    * @throws InterruptedException  if interrupted
    */
   protected final void awaitUntilRemoteTransactionsFinished() throws InterruptedException {
      if (log.isTraceEnabled()) {
         log.tracef("[%s] thread will wait until all remote transaction are finished", Thread.currentThread().getName());
      }
      synchronized (remoteTransactions) {
         while (!remoteTransactions.isEmpty()) {
            remoteTransactions.wait();
         }
      }
      if (log.isTraceEnabled()) {
         log.tracef("[%s] all remote transaction are finished. Moving on...", Thread.currentThread().getName());
      }
   }

   /**
    * Registers a component in the registry under the given type, and injects any dependencies needed.  If a component
    * of this type already exists, it is overwritten.
    *
    * @param component  component to register
    * @param clazz      type of component
    */
   protected final void registerComponent(Object component, Class<?> clazz) {
      if (log.isTraceEnabled()) {
         log.tracef("Register a new component. Object is %s, and class is %s", component, clazz);
      }
      componentRegistry.registerComponent(component, clazz);
   }

   /**
    * it tries to create and register the interceptor.
    * if it exists, it returns the old instance
    * if it does not exits, it register the interceptor in {@param interceptor}
    *
    * @param interceptor      the interceptor to register if it does not exits
    * @param interceptorType  the interceptor type
    * @return                 the instance of the interceptor type
    */
   protected final CommandInterceptor createInterceptor(CommandInterceptor interceptor, Class<? extends CommandInterceptor> interceptorType) {
      if (log.isTraceEnabled()) {
         log.tracef("Create a new interceptor. Class is %s", interceptorType);
      }
      CommandInterceptor chainedInterceptor = getComponent(interceptorType);
      if (chainedInterceptor == null) {
         chainedInterceptor = interceptor;
         registerComponent(interceptor, interceptorType);
      }
      return chainedInterceptor;
   }

   /**
    * Retrieves a component of a specified type from the registry, or null if it cannot be found.
    *
    * @param clazz   type to find
    * @return        component, or null
    */
   protected final <T> T getComponent(Class<? extends T> clazz) {
      return componentRegistry.getComponent(clazz);
   }

   /**
    * broadcast the data for all members in the cluster
    *
    * @param data       the data
    * @param totalOrder if the data should be sent in total order
    */
   protected final void broadcastData(Object data, boolean totalOrder) {
      if (LOCAL_STOP_ACK.equals(data)) {
         throw new IllegalStateException("Cannot broadcast data for protocol " + getUniqueProtocolName() + ". It" +
                                               " is equals to the private data");
      }
      internalBroadcastData(data, totalOrder);
   }

   protected final boolean needsVersionAwareComponents() {
      return configuration.transaction().transactionMode() == TransactionMode.TRANSACTIONAL &&
            configuration.locking().writeSkewCheck() &&
            configuration.transaction().lockingMode() == LockingMode.OPTIMISTIC &&
            configuration.versioning().enabled();
   }

   /**
    * builds the default 2PC interceptor chain based on the configuration
    *
    * @return  the default 2PC interceptor chain
    */
   protected final EnumMap<InterceptorType, CommandInterceptor> buildDefaultInterceptorChain() {
      EnumMap<InterceptorType, CommandInterceptor> defaultIC = new EnumMap<InterceptorType, CommandInterceptor>(InterceptorType.class);
      boolean serializability = configuration.locking().isolationLevel() == IsolationLevel.SERIALIZABLE;

      //State transfer
      // load the state transfer lock interceptor
      // the state transfer lock ensures that the cache member list is up-to-date
      // so it's necessary even if state transfer is disabled
      if (configuration.clustering().cacheMode().isDistributed() || configuration.clustering().cacheMode().isReplicated()) {
         defaultIC.put(InterceptorChain.InterceptorType.STATE_TRANSFER,
                       createInterceptor(new StateTransferLockInterceptor(), StateTransferLockInterceptor.class));
      }

      //Locking
      if (configuration.transaction().transactionMode() == TransactionMode.TRANSACTIONAL) {
         if (serializability) {
            defaultIC.put(InterceptorType.LOCKING,
                          createInterceptor(new OptimisticReadWriteLockingInterceptor(),
                                            OptimisticReadWriteLockingInterceptor.class));
         }  else if (configuration.transaction().lockingMode() == LockingMode.PESSIMISTIC) {
            defaultIC.put(InterceptorType.LOCKING,
                          createInterceptor(new PessimisticLockingInterceptor(), PessimisticLockingInterceptor.class));
         } else {
            defaultIC.put(InterceptorType.LOCKING,
                          createInterceptor(new OptimisticLockingInterceptor(), OptimisticLockingInterceptor.class));
         }
      } else {
         if (configuration.locking().isolationLevel() != IsolationLevel.NONE)
            defaultIC.put(InterceptorType.LOCKING,
                          createInterceptor(new NonTransactionalLockingInterceptor(), NonTransactionalLockingInterceptor.class));
      }

      //Wrapper
      if (serializability) {
         defaultIC.put(InterceptorType.WRAPPER,
                       createInterceptor(new GMUEntryWrappingInterceptor(), GMUEntryWrappingInterceptor.class));
      } else if (needsVersionAwareComponents() && configuration.clustering().cacheMode().isClustered()) {
         defaultIC.put(InterceptorType.WRAPPER,
                       createInterceptor(new VersionedEntryWrappingInterceptor(), VersionedEntryWrappingInterceptor.class));

      } else {
         defaultIC.put(InterceptorType.WRAPPER,
                       createInterceptor(new EntryWrappingInterceptor(), EntryWrappingInterceptor.class));
      }

      //Deadlock
      if (configuration.deadlockDetection().enabled()) {
         defaultIC.put(InterceptorType.DEADLOCK,
                       createInterceptor(new DeadlockDetectingInterceptor(), DeadlockDetectingInterceptor.class));
      }

      //Clustering interceptor
      switch (configuration.clustering().cacheMode()) {
         case REPL_SYNC:
            if (serializability) {
               defaultIC.put(InterceptorType.CLUSTER,
                             createInterceptor(new GMUReplicationInterceptor(), GMUReplicationInterceptor.class));
               break;
            } else if (needsVersionAwareComponents()) {
               defaultIC.put(InterceptorType.CLUSTER,
                             createInterceptor(new VersionedReplicationInterceptor(), VersionedReplicationInterceptor.class));
               break;
            }
         case REPL_ASYNC:
            defaultIC.put(InterceptorType.CLUSTER,
                          createInterceptor(new ReplicationInterceptor(), ReplicationInterceptor.class));
            break;
         case INVALIDATION_SYNC:
         case INVALIDATION_ASYNC:
            defaultIC.put(InterceptorType.CLUSTER,
                          createInterceptor(new InvalidationInterceptor(), InvalidationInterceptor.class));
            break;
         case DIST_SYNC:
            if(serializability) {
               defaultIC.put(InterceptorType.CLUSTER,
                             createInterceptor(new GMUDistributionInterceptor(), GMUDistributionInterceptor.class));
               break;
            } else if (needsVersionAwareComponents()) {
               defaultIC.put(InterceptorType.CLUSTER,
                             createInterceptor(new VersionedDistributionInterceptor(), VersionedDistributionInterceptor.class));
               break;
            }
         case DIST_ASYNC:
            defaultIC.put(InterceptorType.CLUSTER,
                          createInterceptor(new DistributionInterceptor(), DistributionInterceptor.class));
            break;
         case LOCAL:
            //Nothing...
      }

      if (log.isTraceEnabled()) {
         log.tracef("Building default Interceptor Chain: %s", defaultIC);
      }

      return defaultIC;
   }

   /**
    * performs a global stop the world model, ensuring no local neither remote transaction are committing
    *
    * @param totalOrder             if it uses total order
    * @throws InterruptedException  if interrupted
    */
   protected final void globalStopProtocol(boolean totalOrder, boolean abortOnStop) throws InterruptedException {
      /*
      1) block local transactions (already done by Manager)
      2) wait until all local transactions has finished
      3) send message signal the end of transactions
      4) wait for others members messages
      5) wait until all remote transactions has finished
      */
      if (log.isDebugEnabled()) {
         log.debugf("[%s] Performing the global stop protocol. Using total order? %s", Thread.currentThread().getName(),
                    totalOrder);
      }
      awaitUntilLocalExecutingTransactionsFinished(abortOnStop);
      awaitUntilLocalCommittingTransactionsFinished();
      internalBroadcastData(LOCAL_STOP_ACK, totalOrder);
      ackCollector.awaitAllAck();
      awaitUntilRemoteTransactionsFinished();
      if (log.isDebugEnabled()) {
         log.debugf("[%s] Global stop protocol completed. No transaction are committing now",
                    Thread.currentThread().getName());
      }
   }

   /**
    * returns the actual cache members
    * @return  the actual cache members
    */
   protected final Collection<Address> getCacheMembers() {
      return rpcManager.getTransport().getMembers();
   }

   /**
    * returns true it this node is the coordinator, false otherwise
    * @return  true it this node is the coordinator, false otherwise
    */
   protected final boolean isCoordinator() {
      return rpcManager.getTransport().isCoordinator();
   }

   /**
    * throw an exception when this protocol cannot process an old transaction
    */
   protected final void throwOldTxException(GlobalTransaction globalTransaction) {
      manager.addNumberOfAbortedTransactionDueToSwitch(1);
      throw new CacheException("Old transaction '" + globalTransaction.prettyPrint() + "' from " +
                                     getUniqueProtocolName() + " not allowed in current epoch");
   }

   /**
    * throw an exception when this protocol cannot process a speculative transaction
    */
   protected final void throwSpeculativeTxException(GlobalTransaction globalTransaction) {
      manager.addNumberOfAbortedTransactionDueToSwitch(1);
      throw new CacheException("Speculative transaction '" + globalTransaction.prettyPrint() + "' from " +
                                     getUniqueProtocolName() + " not allowed");
   }

   /**
    * logs the normal transaction process
    *
    * @param globalTransaction   the global transaction
    */
   protected final void logProcessTransaction(GlobalTransaction globalTransaction) {
      if (log.isTraceEnabled()) {
         log.tracef("Process transaction '%s'", globalTransaction.prettyPrint());
      }
   }

   /**
    * logs the old transaction process
    *
    * @param globalTransaction   the global transaction
    * @param current             the current protocol
    */
   protected final void logProcessOldTransaction(GlobalTransaction globalTransaction, ReconfigurableProtocol current) {
      if (log.isTraceEnabled()) {
         log.tracef("Process old transaction '%s' and the current protocol is %s",
                    globalTransaction.prettyPrint(), current.getUniqueProtocolName());
      }
   }

   /**
    * logs the speculative transaction process
    *
    * @param globalTransaction   the global transaction
    * @param old                 the old protocol
    */
   protected final void logProcessSpeculativeTransaction(GlobalTransaction globalTransaction, ReconfigurableProtocol old) {
      if (log.isTraceEnabled()) {
         log.tracef("Process speculative transaction '%s' and the old protocol is %s",
                    globalTransaction.prettyPrint(), old.getUniqueProtocolName());
      }
   }

   /**
    * broadcast the data for all members in the cluster
    *
    * @param data       the data
    * @param totalOrder if the data should be sent in total order
    */
   private void internalBroadcastData(Object data, boolean totalOrder) {
      if (log.isTraceEnabled()) {
         log.tracef("Broadcast data. Data is %s, Using total order? %s", data, totalOrder);
      }

      ReconfigurableProtocolCommand command = commandsFactory.buildReconfigurableProtocolCommand(Type.DATA, getUniqueProtocolName());
      command.setData(data);
      rpcManager.broadcastRpcCommand(command, false, totalOrder);
   }

   /**
    * the global unique protocol name
    *
    * @return  the global unique protocol name
    */
   public abstract String getUniqueProtocolName();

   /**
    * returns true if the {@link #switchTo(ReconfigurableProtocol)} can be perform
    * with the new protocol
    *
    * @param protocol   the new protocol
    * @return           true if this protocol can switch directly to the new protocol, false otherwise
    */
   public abstract boolean canSwitchTo(ReconfigurableProtocol protocol);

   /**
    * this method switches between te current protocol to the new protocol without ensure this strong condition:
    *  -- no transaction in the current protocol are running in all the system see {@link #stopProtocol(boolean)}
    *
    * @param protocol   the new protocol
    */
   public abstract void switchTo(ReconfigurableProtocol protocol);

   /**
    * it ensures that no transactions in the current protocol are running in the system (strong condition). this is
    * necessary to switch to any protocol
    * In other words, it means that all transactions active with that protocol in the cluster need to be ended
    * @param abortOnStop   abort local executing transaction (that did not request the commit) otherwise it waits for
    *                      that transactions to finish
    */
   public abstract void stopProtocol(boolean abortOnStop) throws InterruptedException;

   /**
    * it starts this new protocol
    */
   public abstract void bootProtocol();

   /**
    * method invoked before a normal remote transaction will be processed in a safe state
    *
    * @param globalTransaction   the global transaction
    * @param writeSet            the modifications array
    */
   public abstract void processTransaction(GlobalTransaction globalTransaction, WriteCommand[] writeSet);

   /**
    * method invoked before an old remote transaction will be processed
    *
    * @param globalTransaction   the global transaction
    * @param writeSet            the modifications array
    * @param currentProtocol     the current replication protocol
    */
   public abstract void processOldTransaction(GlobalTransaction globalTransaction, WriteCommand[] writeSet, ReconfigurableProtocol currentProtocol);

   /**
    * method invoked before a correct epoch transaction will be processed in an unsafe state
    *
    * @param globalTransaction   the global transaction
    * @param writeSet            the modifications array
    * @param oldProtocol         the old replication protocol
    */
   public abstract void processSpeculativeTransaction(GlobalTransaction globalTransaction, WriteCommand[] writeSet,
                                                      ReconfigurableProtocol oldProtocol);

   /**
    * one of the first methods to be invoked when this protocol is register. It must register all the components added and
    * possible dependencies
    */
   public abstract void bootstrapProtocol();

   /**
    * creates the interceptor chain for this protocol. if some position in the map is null, the interceptor in that
    * position is bypassed. It is possible to add two custom interceptors: one before Tx Interceptor and another after.
    *
    * Note: the interceptor instances should be instances returned by {@link #createInterceptor(org.infinispan.interceptors.base.CommandInterceptor, Class)}
    *
    * @return  the map with the new interceptors
    */
   public abstract EnumMap<InterceptorType, CommandInterceptor> buildInterceptorChain();

   /**
    * check is this local transaction can be committed via 1 phase, instead of 2 phases. In 1 phase, only the prepare
    * message is created
    *
    * @param localTransaction the local transaction that wants finish
    * @return                 true if it can be committed in 1 phase, false otherwise
    */
   public abstract boolean use1PC(LocalTransaction localTransaction);

   /**
    * returns true is the replication protocol uses Total Order properties
    *
    * @return  true is the replication protocol uses Total Order properties
    */
   public abstract boolean useTotalOrder();

   /**
    * method invoked when a message is received for this protocol
    *
    * @param data the data in the message
    * @param from the sender
    */
   protected abstract void internalHandleData(Object data, Address from);

   /**
    * class the collects all the ack from all member, unblocking waiting threads in the end
    */
   protected class AckCollector {
      //NOTE: it is assuming that nodes will no leave neither join the cache during the switch
      private final Set<Address> members;
      private boolean notReady;

      public AckCollector() {
         members = new HashSet<Address>();
         notReady = true;
      }

      public synchronized final void ack(Address from) {
         resetIfNeeded();

         if (log.isDebugEnabled()) {
            log.debugf("Received stop ack from %s", from);
         }
         members.remove(from);
         if (members.isEmpty()) {
            this.notifyAll();
         }
      }

      public synchronized final void awaitAllAck() throws InterruptedException {
         resetIfNeeded();

         if (log.isDebugEnabled()) {
            log.debugf("[%s] thread will wait for all acks...", Thread.currentThread().getName());
         }
         while (!members.isEmpty()) {
            this.wait();
         }
         if (log.isDebugEnabled()) {
            log.debugf("[%s] all acks received. Moving on...", Thread.currentThread().getName());
         }
         notReady = true;
      }

      private void resetIfNeeded() {
         if (notReady) {
            members.clear();
            members.addAll(getCacheMembers());
            members.remove(rpcManager.getAddress());
            notReady = false;
         }
      }
   }
}
