package com.yucareux.tellus.world.realtime;

public final class WeatherTemperaturePolicy {
   // Snow can commonly reach the surface slightly above freezing; 2 C is a practical rain/snow cutoff.
   public static final float MAX_SNOW_TEMPERATURE_C = 2.0F;

   private WeatherTemperaturePolicy() {
   }

   public static boolean hasTemperature(float temperatureC) {
      return Float.isFinite(temperatureC);
   }

   public static boolean shouldSnow(float temperatureC) {
      return hasTemperature(temperatureC) && temperatureC <= MAX_SNOW_TEMPERATURE_C;
   }
}
