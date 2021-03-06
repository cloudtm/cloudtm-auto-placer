/*
 * Copyright 2011 Red Hat, Inc. and/or its affiliates.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 */

package org.infinispan.commands.tx;

import org.infinispan.commands.write.WriteCommand;
import org.infinispan.container.versioning.EntryVersionsMap;
import org.infinispan.transaction.xa.GlobalTransaction;

import java.util.Arrays;
import java.util.List;

/**
 * Same as {@link PrepareCommand} except that the transaction originator makes evident the versions of entries touched
 * and stored in a transaction context so that accurate write skew checks may be performed by the lock owner(s).
 *
 * @author Manik Surtani
 * @since 5.1
 */
public class VersionedPrepareCommand extends PrepareCommand {
   public static final byte COMMAND_ID = 26;
   private EntryVersionsMap versionsSeen = null;

   public VersionedPrepareCommand() {
      super("");
   }

   public VersionedPrepareCommand(String cacheName, GlobalTransaction gtx, List<WriteCommand> modifications, boolean onePhase) {
      // VersionedPrepareCommands are *always* 2-phase, except when retrying a prepare.
      super(cacheName, gtx, modifications, onePhase);
   }

   public VersionedPrepareCommand(String cacheName) {
      super(cacheName);
   }

   public EntryVersionsMap getVersionsSeen() {
      return versionsSeen;
   }

   public void setVersionsSeen(EntryVersionsMap versionsSeen) {
      this.versionsSeen = versionsSeen;
   }

   @Override
   public byte getCommandId() {
      return COMMAND_ID;
   }

   @Override
   public Object[] getParameters() {
      int numMods = modifications == null ? 0 : modifications.length;
      int i = 0;
      final int params = 4;
      Object[] retval = new Object[numMods + params];
      retval[i++] = globalTx;
      retval[i++] = onePhaseCommit;
      retval[i++] = versionsSeen;
      retval[i++] = numMods;
      if (numMods > 0) System.arraycopy(modifications, 0, retval, params, numMods);
      return retval;
   }

   @Override
   @SuppressWarnings("unchecked")
   public void setParameters(int commandId, Object[] args) {
      int i = 0;
      globalTx = (GlobalTransaction) args[i++];
      onePhaseCommit = (Boolean) args[i++];
      versionsSeen = (EntryVersionsMap) args[i++];
      int numMods = (Integer) args[i++];
      if (numMods > 0) {
         modifications = new WriteCommand[numMods];
         System.arraycopy(args, i, modifications, 0, numMods);
      }
   }

   @Override
   public boolean isReturnValueExpected() {
      return true;
   }

   @Override
   public String toString() {
      return "VersionedPrepareCommand {" +
            "modifications=" + getAffectedKeys() +
            ", onePhaseCommit=" + onePhaseCommit +
            ", versionsSeen=" + versionsSeen +
            ", gtx=" + globalTx.prettyPrint() +
            ", cacheName='" + cacheName + '\'' +
            '}';
   }
}
