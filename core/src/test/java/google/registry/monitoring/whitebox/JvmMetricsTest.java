package google.registry.monitoring.whitebox;

import static com.google.common.truth.Truth.assertThat;

import google.registry.monitoring.whitebox.CheckApiMetric.Status;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link JvmMetrics}. */
class JvmMetricsTests {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private MetricRegistry registry;
  private JvmMetrics jvmMetrics;

  @Mock private MemoryMXBean mockMemoryMXBean;

  @Before
  public void setUp() {
    MetricRegistryImpl.getDefault().clear();
    registry = MetricRegistryImpl.getDefault();

    jvmMetrics = new JvmMetrics(mockMemoryMXBean);
  }

  @Test
  public void metricsRegistered() {
    assertThat(registry.getMetric("/jvm/memory/used")).isNotNull();
    assertThat(registry.getMetric("/jvm/memory/committed")).isNotNull();
    assertThat(registry.getMetric("/jvm/memory/max")).isNotNull();

    assertThat(registry.getMetric("/jvm/memory/used")).isInstanceOf(GaugeMetric.class);
  }


  @Test
  public void updateMemoryMetrics_withMockedBean() {
    MemoryUsage heapUsage = new MemoryUsage(100, 200, 500, 1000);
    MemoryUsage nonHeapUsage = new MemoryUsage(50, 100, 250, 500);
    when(mockMemoryMXBean.getHeapMemoryUsage()).thenReturn(heapUsage);
    when(mockMemoryMXBean.getNonHeapMemoryUsage()).thenReturn(nonHeapUsage);

    JvmMetrics testMetrics = new JvmMetrics(mockMemoryMXBean);
    testMetrics.updateMemoryMetrics();

    GaugeMetric<Long> used = (GaugeMetric<Long>) registry.getMetric("/jvm/memory/used");
    GaugeMetric<Long> committed = (GaugeMetric<Long>) registry.getMetric("/jvm/memory/committed");
    GaugeMetric<Long> max = (GaugeMetric<Long>) registry.getMetric("/jvm/memory/max");

    assertThat(used.get("heap")).isEqualTo(100);
    assertThat(committed.get("heap")).isEqualTo(500);
    assertThat(max.get("heap")).isEqualTo(1000);

    assertThat(used.get("non_heap")).isEqualTo(50);
    assertThat(committed.get("non_heap")).isEqualTo(250);
    assertThat(max.get("non_heap")).isEqualTo(500);
  }

}