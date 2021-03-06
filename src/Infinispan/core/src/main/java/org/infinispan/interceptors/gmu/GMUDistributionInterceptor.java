package org.infinispan.interceptors.gmu;

import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.gmu.InternalGMUCacheEntry;
import org.infinispan.container.gmu.L1GMUContainer;
import org.infinispan.container.versioning.VersionGenerator;
import org.infinispan.container.versioning.gmu.GMUVersionGenerator;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.interceptors.DistributionInterceptor;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.transport.Address;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.Collection;
import java.util.Map;

import static org.infinispan.transaction.gmu.GMUHelper.joinAndSetTransactionVersion;
import static org.infinispan.transaction.gmu.GMUHelper.toGMUVersionGenerator;

/**
 * @author Pedro Ruivo
 * @author Sebastiano Peluso
 * @author Hugo Pimentel
 * @since 5.2
 */
public class GMUDistributionInterceptor extends DistributionInterceptor {

   private static final Log log = LogFactory.getLog(GMUDistributionInterceptor.class);
   protected GMUVersionGenerator versionGenerator;
   private L1GMUContainer l1GMUContainer;

   @Inject
   public void setVersionGenerator(VersionGenerator versionGenerator, L1GMUContainer l1GMUContainer) {
      this.versionGenerator = toGMUVersionGenerator(versionGenerator);
      this.l1GMUContainer = l1GMUContainer;
   }

   @Override
   protected void prepareOnAffectedNodes(TxInvocationContext ctx, PrepareCommand command,
                                         Collection<Address> recipients, boolean sync) {
      Map<Address, Response> responses = rpcManager.invokeRemotely(recipients, command, true, true, false);
      log.debugf("prepare command for transaction %s is sent. responses are: %s",
                 command.getGlobalTransaction().prettyPrint(), responses.toString());

      joinAndSetTransactionVersion(responses.values(), ctx, versionGenerator);
   }

   @Override
   protected InternalCacheEntry retrieveFromRemoteSource(Object key, InvocationContext ctx, boolean acquireRemoteLock)
         throws Exception {
      if (ctx instanceof TxInvocationContext) {
         if (log.isTraceEnabled()) {
            log.tracef("Trying to retrieve a the key %s from L1 GMU Data Container", key);
         }
         TxInvocationContext txInvocationContext = (TxInvocationContext) ctx;
         InternalGMUCacheEntry gmuCacheEntry = l1GMUContainer.getValidVersion(key,
                                                                              txInvocationContext.getTransactionVersion(),
                                                                              txInvocationContext.getAlreadyReadFrom());
         if (gmuCacheEntry != null) {
            if (log.isTraceEnabled()) {
               log.tracef("Retrieve a L1 entry for key %s: %s", key, gmuCacheEntry);
            }
            txInvocationContext.addKeyReadInCommand(key, gmuCacheEntry);
            txInvocationContext.addReadFrom(dm.getPrimaryLocation(key));
            return gmuCacheEntry.getInternalCacheEntry();
         }
      }
      if (log.isTraceEnabled()) {
         log.tracef("Failed to retrieve  a L1 entry for key %s", key);
      }
      return super.retrieveFromRemoteSource(key, ctx, acquireRemoteLock);
   }

   @Override
   protected void storeInL1(Object key, InternalCacheEntry ice, InvocationContext ctx, boolean isWrite) throws Throwable {
      if (log.isTraceEnabled()) {
         log.tracef("Doing a put in L1 into the L1 GMU Data Container");
      }
      InternalGMUCacheEntry gmuCacheEntry = ctx.getKeysReadInCommand().get(key);
      if (gmuCacheEntry == null) {
         throw new NullPointerException("GMU cache entry cannot be null");
      }
      l1GMUContainer.insertOrUpdate(key, gmuCacheEntry);
      CacheEntry ce = ctx.lookupEntry(key);
      if (ce == null || ce.isNull() || ce.isLockPlaceholder() || ce.getValue() == null) {
         if (ce != null && ce.isChanged()) {
            ce.setValue(ice.getValue());
         } else {
            if (isWrite)
               entryFactory.wrapEntryForPut(ctx, key, ice, false);
            else
               ctx.putLookedUpEntry(key, ice);
         }
      }
   }
}
