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

package org.infinispan.factories;

import org.infinispan.container.versioning.SimpleClusteredVersionGenerator;
import org.infinispan.container.versioning.VersionGenerator;
import org.infinispan.container.versioning.gmu.DistGMUVersionGenerator;
import org.infinispan.container.versioning.gmu.ReplGMUVersionGenerator;
import org.infinispan.factories.annotations.DefaultFactoryFor;

@DefaultFactoryFor(classes = VersionGenerator.class)
public class VersioningMetaFactory extends AbstractNamedCacheComponentFactory implements AutoInstantiableFactory {
   @SuppressWarnings("unchecked")
   @Override
   public <T> T construct(Class<T> componentType) {
      if (!configuration.isRequireVersioning())
         return null;

      switch (configuration.getVersioningScheme()) {
         case SIMPLE: {
            if (configuration.getCacheMode().isClustered())
               return (T) new SimpleClusteredVersionGenerator();
            else
               return null;
         }         
         case GMU:
            if (configuration.getCacheMode().isReplicated()) {
               return (T) new ReplGMUVersionGenerator();
            } else if (configuration.getCacheMode().isDistributed()) {
               return (T) new DistGMUVersionGenerator();
            }
         default:
            return null;
      }
   }
}
