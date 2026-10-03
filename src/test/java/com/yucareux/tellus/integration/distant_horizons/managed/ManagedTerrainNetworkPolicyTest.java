package com.yucareux.tellus.integration.distant_horizons.managed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ManagedTerrainNetworkPolicyTest {
   @Test
   void cacheOnlyScopesNestAndRestoreThreadState() {
      assertFalse(ManagedTerrainNetworkPolicy.isCacheOnly());
      try (ManagedTerrainNetworkPolicy.Scope outer = ManagedTerrainNetworkPolicy.cacheOnly()) {
         assertTrue(ManagedTerrainNetworkPolicy.isCacheOnly());
         try (ManagedTerrainNetworkPolicy.Scope inner = ManagedTerrainNetworkPolicy.cacheOnly()) {
            assertTrue(ManagedTerrainNetworkPolicy.isCacheOnly());
         }
         assertTrue(ManagedTerrainNetworkPolicy.isCacheOnly());
      }
      assertFalse(ManagedTerrainNetworkPolicy.isCacheOnly());
   }
}
