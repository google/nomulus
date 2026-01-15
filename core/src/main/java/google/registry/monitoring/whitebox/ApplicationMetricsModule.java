// Example ApplicationMetricsModule.java
package google.registry.monitoring.whitebox; // Or a more suitable package

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public final class ApplicationMetricsModule {

  @Provides
  @Singleton
  static JvmMetrics provideJvmMetrics() {
    return new JvmMetrics(registry);
  }
}