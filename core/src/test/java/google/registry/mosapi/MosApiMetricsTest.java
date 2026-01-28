// Copyright 2026 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package google.registry.mosapi;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.monitoring.v3.Monitoring;
import com.google.api.services.monitoring.v3.model.CreateTimeSeriesRequest;
import com.google.api.services.monitoring.v3.model.MetricDescriptor;
import com.google.api.services.monitoring.v3.model.TimeSeries;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.mosapi.MosApiModels.ServiceStatus;
import google.registry.mosapi.MosApiModels.TldServiceState;
import google.registry.request.lock.LockHandler;
import google.registry.testing.FakeClock;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link MosApiMetrics}. */
public class MosApiMetricsTest {
  private static final String PROJECT_ID = "domain-registry-test";

  private final Monitoring monitoringClient = mock(Monitoring.class);
  private final Monitoring.Projects projects = mock(Monitoring.Projects.class);
  private final LockHandler lockHandler = mock(LockHandler.class);
  private final Monitoring.Projects.TimeSeries timeSeriesResource =
      mock(Monitoring.Projects.TimeSeries.class);
  private final Monitoring.Projects.TimeSeries.Create createRequest =
      mock(Monitoring.Projects.TimeSeries.Create.class);

  // Mocks for Metric Descriptors
  private final Monitoring.Projects.MetricDescriptors metricDescriptorsResource =
      mock(Monitoring.Projects.MetricDescriptors.class);
  private final Monitoring.Projects.MetricDescriptors.Create createDescriptorRequest =
      mock(Monitoring.Projects.MetricDescriptors.Create.class);

  // Fixed Clock for deterministic testing
  private final FakeClock clock = new FakeClock(DateTime.parse("2026-01-01T12:00:00Z"));
  private MosApiMetrics mosApiMetrics;


  @BeforeEach
  void setUp() throws IOException {
    when(monitoringClient.projects()).thenReturn(projects);
    when(projects.timeSeries()).thenReturn(timeSeriesResource);
    when(timeSeriesResource.create(anyString(), any(CreateTimeSeriesRequest.class)))
        .thenReturn(createRequest);

    // Setup for Metric Descriptors
    when(projects.metricDescriptors()).thenReturn(metricDescriptorsResource);
    when(metricDescriptorsResource.create(anyString(), any(MetricDescriptor.class)))
        .thenReturn(createDescriptorRequest);
    when(lockHandler.executeWithLocks(any(Callable.class), any(), any(), any()))
        .thenAnswer(invocation -> {
          ((Callable<?>) invocation.getArgument(0)).call();
          return true;
        });
    mosApiMetrics = new MosApiMetrics(monitoringClient, PROJECT_ID, clock,lockHandler);
  }

  @Test
  void testRecordStates_lazilyInitializesMetricDescriptors() throws IOException {
    TldServiceState state = createTldState("test.tld", "UP", "UP");

    mosApiMetrics.recordStates(ImmutableList.of(state));

    ArgumentCaptor<MetricDescriptor> captor = ArgumentCaptor.forClass(MetricDescriptor.class);

    verify(metricDescriptorsResource, times(3))
        .create(eq("projects/" + PROJECT_ID), captor.capture());

    List<MetricDescriptor> descriptors = captor.getAllValues();

    // Verify TLD Status Descriptor
    MetricDescriptor tldStatus =
        descriptors.stream()
            .filter(d -> d.getType().endsWith("tld_status"))
            .findFirst()
            .orElseThrow();
    assertThat(tldStatus.getMetricKind()).isEqualTo("GAUGE");
    assertThat(tldStatus.getValueType()).isEqualTo("INT64");

    // Verify Service Status Descriptor
    MetricDescriptor serviceStatus =
        descriptors.stream()
            .filter(d -> d.getType().endsWith("service_status"))
            .findFirst()
            .orElseThrow();
    assertThat(serviceStatus.getMetricKind()).isEqualTo("GAUGE");
    assertThat(serviceStatus.getValueType()).isEqualTo("INT64");

    // Verify Emergency Usage Descriptor
    MetricDescriptor emergencyUsage =
        descriptors.stream()
            .filter(d -> d.getType().endsWith("emergency_usage"))
            .findFirst()
            .orElseThrow();
    assertThat(emergencyUsage.getMetricKind()).isEqualTo("GAUGE");
    assertThat(emergencyUsage.getValueType()).isEqualTo("DOUBLE");
  }

  @Test
  void testRecordStates_mapsStatusesToCorrectValues() throws IOException {
    TldServiceState stateUp = createTldState("tld-up", "UP", "UP");
    TldServiceState stateDown = createTldState("tld-down", "DOWN", "DOWN");
    TldServiceState stateMaint = createTldState("tld-maint", "UP-INCONCLUSIVE", "DISABLED");

    mosApiMetrics.recordStates(ImmutableList.of(stateUp, stateDown, stateMaint));

    ArgumentCaptor<CreateTimeSeriesRequest> captor =
        ArgumentCaptor.forClass(CreateTimeSeriesRequest.class);
    verify(timeSeriesResource).create(eq("projects/" + PROJECT_ID), captor.capture());

    List<TimeSeries> pushedSeries = captor.getValue().getTimeSeries();

    // Verify TLD Status Mappings: 1 (UP), 0 (DOWN), 2 (UP-INCONCLUSIVE)
    assertThat(getValueFor(pushedSeries, "tld-up", "tld_status")).isEqualTo(1);
    assertThat(getValueFor(pushedSeries, "tld-down", "tld_status")).isEqualTo(0);
    assertThat(getValueFor(pushedSeries, "tld-maint", "tld_status")).isEqualTo(2);

    // Verify Service Status Mappings: UP -> 1, DOWN -> 0, DISABLED -> 2
    assertThat(getValueFor(pushedSeries, "tld-up", "service_status")).isEqualTo(1);
    assertThat(getValueFor(pushedSeries, "tld-down", "service_status")).isEqualTo(0);
    assertThat(getValueFor(pushedSeries, "tld-maint", "service_status")).isEqualTo(2);

    // 3. Verify Emergency Usage (DOUBLE)
    assertThat(getValueFor(pushedSeries, "tld-up", "emergency_usage").doubleValue())
        .isEqualTo(50.0);
    assertThat(getValueFor(pushedSeries, "tld-down", "emergency_usage").doubleValue())
        .isEqualTo(50.0);
    assertThat(getValueFor(pushedSeries, "tld-maint", "emergency_usage").doubleValue())
        .isEqualTo(50.0);
  }

  @Test
  void testRecordStates_partitionsTimeSeries_atLimit() throws IOException {
    ImmutableList<TldServiceState> largeBatch =
        java.util.stream.IntStream.range(0, 70)
            .mapToObj(i -> createTldState("tld-" + i, "UP", "UP"))
            .collect(ImmutableList.toImmutableList());
    mosApiMetrics.recordStates(largeBatch);

    verify(timeSeriesResource, times(2))
        .create(eq("projects/" + PROJECT_ID), any(CreateTimeSeriesRequest.class));
  }

  @Test
  void testMetricStructure_containsExpectedLabelsAndResource() throws IOException {
    TldServiceState state = createTldState("example.tld", "UP", "UP");
    mosApiMetrics.recordStates(ImmutableList.of(state));

    ArgumentCaptor<CreateTimeSeriesRequest> captor =
        ArgumentCaptor.forClass(CreateTimeSeriesRequest.class);
    verify(timeSeriesResource).create(anyString(), captor.capture());

    TimeSeries ts = captor.getValue().getTimeSeries().get(0);

    assertThat(ts.getMetric().getType()).startsWith("custom.googleapis.com/mosapi/");
    assertThat(ts.getMetric().getLabels()).containsEntry("tld", "example.tld");

    assertThat(ts.getResource().getType()).isEqualTo("global");
    assertThat(ts.getResource().getLabels()).containsEntry("project_id", PROJECT_ID);

    // Verify that the interval matches our fixed clock
    assertThat(ts.getPoints().get(0).getInterval().getEndTime()).isEqualTo("2026-01-01T12:00:00Z");
  }

  /** Extracts the numeric value for a specific TLD and metric type from a list of TimeSeries. */
  private Number getValueFor(List<TimeSeries> seriesList, String tld, String metricSuffix) {
    String fullMetric = "custom.googleapis.com/mosapi/" + metricSuffix;
    return seriesList.stream()
        .filter(ts -> tld.equals(ts.getMetric().getLabels().get("tld")))
        .filter(ts -> ts.getMetric().getType().equals(fullMetric))
        .findFirst()
        .map(
            ts -> {
              Double dVal = ts.getPoints().get(0).getValue().getDoubleValue();
              if (dVal != null) {
                return (Number) dVal;
              }
              // Fallback to Int64.
              return (Number) ts.getPoints().get(0).getValue().getInt64Value();
            })
        .get();
  }
  @Test
  void testRecordStates_skipsInitialization_ifLockNotAcquired() throws IOException {
    when(lockHandler.executeWithLocks(any(Callable.class), any(), any(), any()))
        .thenReturn(false);

    TldServiceState state = createTldState("test.tld", "UP", "UP");
    mosApiMetrics.recordStates(ImmutableList.of(state));

    verify(metricDescriptorsResource, never()).create(anyString(), any());
  }

  /** Mocks a TldServiceState with a single service status. */
  private TldServiceState createTldState(String tld, String tldStatus, String serviceStatus) {
    ServiceStatus sStatus = mock(ServiceStatus.class);
    when(sStatus.status()).thenReturn(serviceStatus);
    when(sStatus.emergencyThreshold()).thenReturn(50.0);

    TldServiceState state = mock(TldServiceState.class);
    when(state.tld()).thenReturn(tld);
    when(state.status()).thenReturn(tldStatus);
    when(state.serviceStatuses()).thenReturn(ImmutableMap.of("dns", sStatus));

    return state;
  }
}
