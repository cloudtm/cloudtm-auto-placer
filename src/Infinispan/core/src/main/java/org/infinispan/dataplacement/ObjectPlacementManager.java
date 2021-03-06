package org.infinispan.dataplacement;

import org.infinispan.commons.hash.Hash;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.distribution.ch.DataPlacementConsistentHash;
import org.infinispan.remoting.transport.Address;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Collects all the remote and local access for each member for the key in which this member is the 
 * primary owner
 *
 * @author Zhongmiao Li
 * @author João Paiva
 * @author Pedro Ruivo
 * @since 5.2
 */
public class ObjectPlacementManager {

   private static final Log log = LogFactory.getLog(ObjectPlacementManager.class);

   private ClusterSnapshot clusterSnapshot;

   private ObjectRequest[] objectRequests;

   private final BitSet requestReceived;

   //this can be quite big. save it as an array to save some memory
   private Object[] allKeysMoved;

   private final DistributionManager distributionManager;
   private final Hash hash;
   private final int defaultNumberOfOwners;

   public ObjectPlacementManager(DistributionManager distributionManager, Hash hash, int defaultNumberOfOwners){
      this.distributionManager = distributionManager;
      this.hash = hash;
      this.defaultNumberOfOwners = defaultNumberOfOwners;

      requestReceived = new BitSet();
      allKeysMoved = new Object[0];
   }

   /**
    * reset the state (before each round)
    *
    * @param roundClusterSnapshot the current cluster members
    */
   public final synchronized void resetState(ClusterSnapshot roundClusterSnapshot) {
      clusterSnapshot = roundClusterSnapshot;
      objectRequests = new ObjectRequest[clusterSnapshot.size()];
      requestReceived.clear();
   }

   /**
    * collects the local and remote accesses for each member
    *
    * @param member        the member that sent the {@code objectRequest}
    * @param objectRequest the local and remote accesses
    * @return              true if all requests are received, false otherwise. It only returns true on the first
    *                      time it has all the objects
    */
   public final synchronized boolean aggregateRequest(Address member, ObjectRequest objectRequest) {
      if (hasReceivedAllRequests()) {
         return false;
      }

      int senderIdx = clusterSnapshot.indexOf(member);

      if (senderIdx < 0) {
         log.warnf("Received request list from %s but it does not exits in %s", member, clusterSnapshot);
         return false;
      }

      objectRequests[senderIdx] = objectRequest;
      requestReceived.set(senderIdx);

      logRequestReceived(member, objectRequest);

      return hasReceivedAllRequests();
   }

   /**
    * calculate the new owners based on the requests received.
    *
    * @return  a map with the keys to be moved and the new owners
    */
   public final synchronized Map<Object, OwnersInfo> calculateObjectsToMove() {
      Map<Object, OwnersInfo> newOwnersMap = new HashMap<Object, OwnersInfo>();

      for (int requesterIdx = 0; requesterIdx < clusterSnapshot.size(); ++requesterIdx) {
         ObjectRequest objectRequest = objectRequests[requesterIdx];

         if (objectRequest == null) {
            continue;
         }

         Map<Object, Long> requestedObjects = objectRequest.getRemoteAccesses();

         for (Map.Entry<Object, Long> entry : requestedObjects.entrySet()) {
            calculateNewOwners(newOwnersMap, entry.getKey(), entry.getValue(), requesterIdx);
         }
         //release memory asap
         requestedObjects.clear();
      }

      removeNotMovedObjects(newOwnersMap);

      //process the old moved keys. this will set the new owners of the previous rounds
      for (Object key : allKeysMoved) {
         if (!newOwnersMap.containsKey(key)) {
            newOwnersMap.put(key, createOwnersInfo(key));
         }
      }

      //update all the keys moved array
      allKeysMoved = newOwnersMap.keySet().toArray(new Object[newOwnersMap.size()]);

      return newOwnersMap;
   }

   /**
    * returns all keys moved so far
    *
    * @return  all keys moved so far
    */
   public final Collection<Object> getKeysToMove() {
      return Arrays.asList(allKeysMoved);
   }

   /**
    * for each object to move, it checks if the owners are different from the owners returned by the original
    * Infinispan's consistent hash. If this is true, the object is removed from the map {@code newOwnersMap}
    *
    * @param newOwnersMap  the map with the key to be moved and the new owners
    */
   private void removeNotMovedObjects(Map<Object, OwnersInfo> newOwnersMap) {
      ConsistentHash defaultConsistentHash = getDefaultConsistentHash();
      Iterator<Map.Entry<Object, OwnersInfo>> iterator = newOwnersMap.entrySet().iterator();

      //if the owners info corresponds to the default consistent hash owners, remove the key from the map 
      mainLoop: while (iterator.hasNext()) {
         Map.Entry<Object, OwnersInfo> entry = iterator.next();
         Object key = entry.getKey();
         OwnersInfo ownersInfo = entry.getValue();
         Collection<Integer> ownerInfoIndexes = ownersInfo.getNewOwnersIndexes();
         Collection<Address> defaultOwners = defaultConsistentHash.locate(key, defaultNumberOfOwners);

         if (ownerInfoIndexes.size() != defaultOwners.size()) {
            continue;
         }

         for (Address address : defaultOwners) {
            if (!ownerInfoIndexes.contains(clusterSnapshot.indexOf(address))) {
               continue mainLoop;
            }
         }
         iterator.remove();
      }
   }

   /**
    * updates the owner information for the {@code key} based in the {@code numberOfRequests} made by the member who
    * requested this {@code key} (identified by {@code requesterId})
    *
    * @param newOwnersMap     the new owners map to be updated
    * @param key              the key requested
    * @param numberOfRequests the number of accesses made to this key
    * @param requesterId      the member id
    */
   private void calculateNewOwners(Map<Object, OwnersInfo> newOwnersMap, Object key, long numberOfRequests, int requesterId) {
      OwnersInfo newOwnersInfo = newOwnersMap.get(key);

      if (newOwnersInfo == null) {
         newOwnersInfo = createOwnersInfo(key);
         newOwnersMap.put(key, newOwnersInfo);
      }
      newOwnersInfo.calculateNewOwner(requesterId, numberOfRequests);
   }

   /**
    * returns the local accesses and owners for the {@code key}
    *
    * @param key  the key
    * @return     the local accesses and owners for the key     
    */
   private Map<Integer, Long> getLocalAccesses(Object key) {
      Map<Integer, Long> localAccessesMap = new TreeMap<Integer, Long>();

      for (int memberIndex = 0; memberIndex < objectRequests.length; ++memberIndex) {
         ObjectRequest request = objectRequests[memberIndex];
         if (request == null) {
            continue;
         }
         Long localAccesses = request.getLocalAccesses().remove(key);
         if (localAccesses != null) {
            localAccessesMap.put(memberIndex, localAccesses);
         }
      }

      return localAccessesMap;
   }

   /**
    * creates a new owners information initialized with the current owners returned by the current consistent hash
    * and their number of accesses for the {@code key}
    *
    * @param key  the key
    * @return     the new owners information.
    */
   private OwnersInfo createOwnersInfo(Object key) {
      Collection<Address> replicas = distributionManager.locate(key);
      Map<Integer, Long> localAccesses = getLocalAccesses(key);

      OwnersInfo ownersInfo = new OwnersInfo(replicas.size());

      for (Address currentOwner : replicas) {
         int ownerIndex = clusterSnapshot.indexOf(currentOwner);

         if (ownerIndex == -1) {
            ownerIndex = findNewOwner(key, replicas);
         }

         Long accesses = localAccesses.remove(ownerIndex);

         if (accesses == null) {
            //TODO check if this should be zero or the min number of local accesses from the member
            accesses = 0L;
         }

         ownersInfo.add(ownerIndex, accesses);
      }

      return ownersInfo;
   }

   /**
    * finds the new owner for the {@code key} based on the Infinispan's consistent hash. this is invoked
    * when the one or more current owners are not in the cluster anymore and it is necessary to find new owners
    * to respect the default number of owners per key
    *
    * @param key           the key
    * @param alreadyOwner  the current owners
    * @return              the new owner index
    */
   private int findNewOwner(Object key, Collection<Address> alreadyOwner) {
      int size = clusterSnapshot.size();

      if (size <= 1) {
         return 0;
      }

      int startIndex = hash.hash(key) % size;

      for (int index = startIndex + 1; index != startIndex; index = (index + 1) % size) {
         if (!alreadyOwner.contains(clusterSnapshot.get(index))) {
            return index;
         }
         index = (index + 1) % size;
      }

      return 0;
   }

   /**
    * returns the actual consistent hashing
    *
    * @return  the actual consistent hashing
    */
   private ConsistentHash getDefaultConsistentHash() {
      ConsistentHash hash = this.distributionManager.getConsistentHash();
      return hash instanceof DataPlacementConsistentHash ?
            ((DataPlacementConsistentHash) hash).getDefaultHash() :
            hash;
   }

   private boolean hasReceivedAllRequests() {
      return requestReceived.cardinality() == clusterSnapshot.size();
   }

   private void logRequestReceived(Address sender, ObjectRequest request) {
      if (log.isTraceEnabled()) {
         StringBuilder missingMembers = new StringBuilder();

         for (int i = 0; i < clusterSnapshot.size(); ++i) {
            if (!requestReceived.get(i)) {
               missingMembers.append(clusterSnapshot.get(i)).append(" ");
            }
         }

         log.debugf("Object Request received from %s. Missing request are %s. The Object Request is %s", sender,
                    missingMembers, request.toString(true));
      } else if (log.isDebugEnabled()) {
         log.debugf("Object Request received from %s. Missing request are %s. The Object Request is %s", sender,
                    (clusterSnapshot.size() - requestReceived.cardinality()), request.toString());
      }
   }

}

