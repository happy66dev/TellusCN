package com.yucareux.tellus.integration.distant_horizons;

import java.util.concurrent.atomic.AtomicBoolean;

final class DistantHorizonsStartupGate {
   private final Object monitor = new Object();
   private final AtomicBoolean ready = new AtomicBoolean(false);
   private long generation;
   private int waitingCount;

   boolean isReady() {
      return this.ready.get();
   }

   boolean release() {
      synchronized (this.monitor) {
         boolean changed = this.ready.compareAndSet(false, true);
         if (changed) {
            this.monitor.notifyAll();
         }
         return changed;
      }
   }

   void reset() {
      synchronized (this.monitor) {
         this.generation++;
         this.ready.set(false);
         this.monitor.notifyAll();
      }
   }

   /**
    * Waits for the current server's initial player position. A reset cancels
    * waiters from the previous server so DH worker threads cannot leak across
    * integrated-server restarts.
    *
    * @return {@code true} when released, or {@code false} when reset first
    */
   boolean awaitReady() throws InterruptedException {
      synchronized (this.monitor) {
         long observedGeneration = this.generation;
         this.waitingCount++;
         try {
            while (!this.ready.get() && observedGeneration == this.generation) {
               this.monitor.wait();
            }
            return this.ready.get() && observedGeneration == this.generation;
         } finally {
            this.waitingCount--;
         }
      }
   }

   int waitingCount() {
      synchronized (this.monitor) {
         return this.waitingCount;
      }
   }
}
