package google.registry.monitoring.whitebox;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;


/** Exposes JVM metrics. */
@Singleton
class JvmMetrics {

  private static final LabelDescriptor TYPE_LABEL = LabelDescriptor.create("type", "Memory type (e.g., heap, non_heap)");

  // Memory Metrics
  private final GaugeMetric<Long> memoryUsed;
  private final GaugeMetric<Long> memoryCommitted;
  private final GaugeMetric<Long> memoryMax;

  private final MemoryMXBean memoryMxBean;
  long heapUsed = heapUsage.getUsed();
  long heapMax = heapUsage.getMax();
  long nonHeapUsed = nonHeapUsage.getUsed();


  @Inject
  JvmMetrics() {
    this(ManagementFactory.getMemoryMXBean());
  }

  // Constructor for testing
  JvmMetrics(MemoryMXBean memoryMxBean) {
    this.memoryMxBean = memoryMxBean;
    MetricRegistry registry = MetricRegistryImpl.getDefault();

    memoryUsed =
        registry.newGaugeMetric(
            "/jvm/memory/used",
            "Current memory usage in bytes",
            "bytes",
            null,
            TYPE_LABEL);

    memoryCommitted =
        registry.newGaugeMetric(
            "/jvm/memory/committed",
            "Committed memory in bytes",
            "bytes",
            null,
            TYPE_LABEL);

    memoryMax =
        registry.newGaugeMetric(
            "/jvm/memory/max",
            "Maximum memory in bytes",
            "bytes",
            null,
            TYPE_LABEL);

    registry.registerCallback(this::updateMemoryMetrics);
  }

  private void updateMemoryMetrics() {
    // Heap Memory
    MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
    memoryUsed.set(heapUsage.getUsed(), "heap");
    memoryCommitted.set(heapUsage.getCommitted(), "heap");
    memoryMax.set(heapUsage.getMax(), "heap");

    // Non-Heap Memory
    MemoryUsage nonHeapUsage = memoryMxBean.getNonHeapMemoryUsage();
    memoryUsed.set(nonHeapUsage.getUsed(), "non_heap");
    memoryCommitted.set(nonHeapUsage.getCommitted(), "non_heap");
    memoryMax.set(nonHeapUsage.getMax(), "non_heap");
  }

}