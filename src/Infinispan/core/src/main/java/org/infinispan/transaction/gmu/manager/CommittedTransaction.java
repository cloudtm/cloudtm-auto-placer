package org.infinispan.transaction.gmu.manager;

import org.infinispan.commands.write.WriteCommand;
import org.infinispan.container.versioning.EntryVersion;
import org.infinispan.transaction.xa.CacheTransaction;

import java.util.Collection;
import java.util.LinkedList;

/**
 * // TODO: Document this
 *
 * @author Pedro Ruivo
 * @since 5.2
 */
public class CommittedTransaction {

   private final EntryVersion commitVersion;
   private final Collection<WriteCommand> modifications;
   private final int subVersion;

   public CommittedTransaction(CacheTransaction cacheTransaction, int subVersion) {
      this.subVersion = subVersion;
      commitVersion = cacheTransaction.getTransactionVersion();
      modifications = new LinkedList<WriteCommand>(cacheTransaction.getModifications());
   }

   public final EntryVersion getCommitVersion() {
      return commitVersion;
   }

   public final Collection<WriteCommand> getModifications() {
      return modifications;
   }

   public final int getSubVersion() {
      return subVersion;
   }
}
