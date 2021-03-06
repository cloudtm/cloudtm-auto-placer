package org.infinispan.dataplacement.c50.tree.node;

import org.infinispan.dataplacement.c50.keyfeature.Feature;
import org.infinispan.dataplacement.c50.keyfeature.FeatureValue;
import org.infinispan.dataplacement.c50.tree.ParseTreeNode;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * Type 3 decision tree node: discrete attributes when possible values are in describe in the rules
 *
 * @author Pedro Ruivo
 * @since 5.2
 */
public class Type3Node implements DecisionTreeNode {

   private static final Log log = LogFactory.getLog(Type3Node.class);

   private final int value;
   private final Feature feature;
   private final DecisionTreeNode[] forks;
   private final InternalEltsValues[] values;

   public Type3Node(int value, Feature feature, DecisionTreeNode[] forks, ParseTreeNode.EltsValues[] eltsValues) {
      this.value = value;
      this.feature = feature;

      if (forks == null || forks.length == 0) {
         throw new IllegalArgumentException("Expected a non-null with at least one fork");
      }

      if (forks.length != eltsValues.length) {
         throw new IllegalArgumentException("Expected same number of forks as options");
      }

      this.forks = forks;
      values = new InternalEltsValues[eltsValues.length - 1];

      //the first value is the N/A that it is always in the forks[0]
      for (int i = 0; i < values.length; ++i) {
         values[i] = new InternalEltsValues(eltsValues[i + 1].getValues(), feature);
      }
   }

   @Override
   public DecisionTreeNode find(Map<Feature, FeatureValue> keyFeatures) {
      if (log.isTraceEnabled()) {
         log.tracef("Try to find key [%s] with feature %s", keyFeatures, feature);
      }

      FeatureValue keyValue = keyFeatures.get(feature);
      if (keyValue == null) { //N/A
         if (log.isTraceEnabled()) {
            log.tracef("Feature Not Available...");
         }
         return forks[0];
      }

      if (log.isTraceEnabled()) {
         log.tracef("Comparing key value [%s]  with possible values %s", keyValue, values);
      }

      for (int i = 0; i < values.length; ++i) {
         if (values[i].match(keyValue)) {
            if (log.isTraceEnabled()) {
               log.tracef("Next decision tree found. The value in %s matched", i);
            }
            return forks[i + 1];
         }
      }

      throw new IllegalStateException("Expected one value match");
   }

   @Override
   public int getValue() {
      return value;
   }

   @Override
   public int getDeep() {
      int maxDeep = 0;
      for (DecisionTreeNode decisionTreeNode : forks) {
         maxDeep = Math.max(maxDeep, decisionTreeNode.getDeep());
      }
      return maxDeep + 1;
   }

   public static class InternalEltsValues implements Serializable {
      private final FeatureValue[] values;

      public InternalEltsValues(Collection<String> eltsValues, Feature feature) {
         values = new FeatureValue[eltsValues.size()];
         int index = 0;
         for (String value : eltsValues) {
            values[index++] = feature.featureValueFromParser(value);
         }
      }

      private boolean match(FeatureValue keyValue) {
         for (FeatureValue value : values) {
            if (value.isEquals(keyValue)) {
               return true;
            }
         }
         return false;
      }

      @Override
      public String toString() {
         return "InternalEltsValues{" +
               "values=" + (values == null ? null : Arrays.asList(values)) +
               '}';
      }
   }
}