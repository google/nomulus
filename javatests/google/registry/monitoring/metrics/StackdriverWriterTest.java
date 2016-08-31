// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.monitoring.metrics;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.monitoring.v3.Monitoring;
import com.google.api.services.monitoring.v3.model.CreateTimeSeriesRequest;
import com.google.api.services.monitoring.v3.model.MetricDescriptor;
import com.google.api.services.monitoring.v3.model.MonitoredResource;
import com.google.api.services.monitoring.v3.model.Point;
import com.google.api.services.monitoring.v3.model.TimeSeries;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.monitoring.metrics.MetricSchema.Kind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link StackdriverWriter}. */
@RunWith(MockitoJUnitRunner.class)
public class StackdriverWriterTest {

  @Mock private Monitoring client;
  @Mock private Monitoring.Projects projects;
  @Mock private Monitoring.Projects.MetricDescriptors metricDescriptors;
  @Mock private Monitoring.Projects.MetricDescriptors.Get metricDescriptorGet;
  @Mock private Monitoring.Projects.TimeSeries timeSeries;
  @Mock private Monitoring.Projects.MetricDescriptors.Create metricDescriptorCreate;
  @Mock private Monitoring.Projects.TimeSeries.Create timeSeriesCreate;
  @Mock private Metric<Long> mockMetric;
  @Mock private MetricSchema schema;
  @Mock MetricPoint<Long> metricPoint;
  private Counter metric;
  private MetricDescriptor descriptor;
  private static final String PROJECT = "PROJECT";
  private static final int MAX_QPS = 10;
  private static final int MAX_POINTS_PER_REQUEST = 10;
  private static final MonitoredResource MONITORED_RESOURCE = new MonitoredResource();

  @Before
  public void setUp() throws Exception {
    metric =
        new Counter(
            "/name",
            "desc",
            "vdn",
            ImmutableSet.of(LabelDescriptor.create("label", "description")));
    descriptor = StackdriverWriter.createMetricDescriptor(metric);
    when(client.projects()).thenReturn(projects);
    when(projects.metricDescriptors()).thenReturn(metricDescriptors);
    when(projects.timeSeries()).thenReturn(timeSeries);
    when(metricDescriptors.create(anyString(), any(MetricDescriptor.class)))
        .thenReturn(metricDescriptorCreate);
    when(metricDescriptorCreate.execute()).thenReturn(descriptor);
    when(metricDescriptors.get(anyString())).thenReturn(metricDescriptorGet);
    when(metricDescriptorGet.execute()).thenReturn(descriptor);
    when(timeSeries.create(anyString(), any(CreateTimeSeriesRequest.class)))
        .thenReturn(timeSeriesCreate);
  }

  @Test
  public void testWrite_maxPoints_flushes() throws Exception {
    // The counter must be set once in order for there to be values to send.
    metric.set(0L, new Instant(1337), ImmutableList.of("some_value"));
    StackdriverWriter writer =
        spy(
            new StackdriverWriter(
                client, PROJECT, MONITORED_RESOURCE, MAX_QPS, MAX_POINTS_PER_REQUEST));

    for (int i = 0; i < MAX_POINTS_PER_REQUEST; i++) {
      for (MetricPoint<?> point : metric.getTimestampedValues(new Instant(1337))) {
        writer.write(point);
      }
    }

    verify(writer).flush();
  }

  @Test
  public void testWrite_lessThanMaxPoints_doesNotFlush() throws Exception {
    // The counter must be set once in order for there to be values to send.
    metric.set(0L, new Instant(1337), ImmutableList.of("some_value"));
    StackdriverWriter writer =
        spy(
            new StackdriverWriter(
                client, PROJECT, MONITORED_RESOURCE, MAX_QPS, MAX_POINTS_PER_REQUEST));

    for (int i = 0; i < MAX_POINTS_PER_REQUEST - 1; i++) {
      for (MetricPoint<?> point : metric.getTimestampedValues(new Instant(1337))) {
        writer.write(point);
      }
    }

    verify(writer, never()).flush();
  }

  @Test
  public void testWrite_invalidMetricType_throwsException() throws Exception {
    when(mockMetric.getValueClass())
        .thenAnswer(
            new Answer<Class<?>>() {
              @Override
              public Class<?> answer(InvocationOnMock invocation) throws Throwable {
                return Object.class;
              }
            });
    when(mockMetric.getMetricSchema()).thenReturn(schema);
    when(mockMetric.getTimestampedValues()).thenReturn(ImmutableList.of(metricPoint));
    when(schema.kind()).thenReturn(Kind.CUMULATIVE);
    when(metricPoint.metric()).thenReturn(mockMetric);
    StackdriverWriter writer =
        new StackdriverWriter(client, PROJECT, MONITORED_RESOURCE, MAX_QPS, MAX_POINTS_PER_REQUEST);

    for (MetricPoint<?> point : mockMetric.getTimestampedValues()) {
      try {
        writer.write(point);
        fail("expected IllegalArgumentException");
      } catch (IOException expected) {}
    }
  }

  @Test
  public void testWrite_ManyPoints_flushesTwice() throws Exception {
    // The counter must be set once in order for there to be values to send.
    metric.set(0L, new Instant(1337), ImmutableList.of("some_value"));
    StackdriverWriter writer =
        spy(
            new StackdriverWriter(
                client, PROJECT, MONITORED_RESOURCE, MAX_QPS, MAX_POINTS_PER_REQUEST));

    for (int i = 0; i < MAX_POINTS_PER_REQUEST * 2; i++) {
      for (MetricPoint<?> point : metric.getTimestampedValues(new Instant(1337))) {
        writer.write(point);
      }
    }

    verify(writer, times(2)).flush();
  }

  @Test
  public void testRegisterMetric_registersWithStackdriver() throws Exception {
    StackdriverWriter writer =
        new StackdriverWriter(client, PROJECT, MONITORED_RESOURCE, MAX_QPS, MAX_POINTS_PER_REQUEST);

    writer.registerMetric(metric);

    verify(
            client
                .projects()
                .metricDescriptors()
                .create(PROJECT, StackdriverWriter.createMetricDescriptor(metric)))
        .execute();
  }

  @Test
  public void registerMetric_doesNotReregisterDupe() throws Exception {
    StackdriverWriter writer =
        new StackdriverWriter(client, PROJECT, MONITORED_RESOURCE, MAX_QPS, MAX_POINTS_PER_REQUEST);

    writer.registerMetric(metric);
    writer.registerMetric(metric);

    verify(
            client
                .projects()
                .metricDescriptors()
                .create(PROJECT, StackdriverWriter.createMetricDescriptor(metric)))
        .execute();
  }

  @Test
  public void registerMetric_fetchesStackdriverDefinition() throws Exception {
    // Stackdriver throws an Exception with the status message "ALREADY_EXISTS" when you try to
    // register a metric that's already been registered, so we fake one here.
    ByteArrayInputStream inputStream = new ByteArrayInputStream("".getBytes(UTF_8));
    HttpResponse response = GoogleJsonResponseExceptionHelper.createHttpResponse(400, inputStream);
    HttpResponseException.Builder httpResponseExceptionBuilder =
        new HttpResponseException.Builder(response);
    httpResponseExceptionBuilder.setStatusCode(400);
    httpResponseExceptionBuilder.setStatusMessage("ALREADY_EXISTS");
    GoogleJsonResponseException exception =
        new GoogleJsonResponseException(httpResponseExceptionBuilder, null);
    when(metricDescriptorCreate.execute()).thenThrow(exception);
    StackdriverWriter writer =
        new StackdriverWriter(client, PROJECT, MONITORED_RESOURCE, MAX_QPS, MAX_POINTS_PER_REQUEST);

    writer.registerMetric(metric);

    verify(client.projects().metricDescriptors().get("metric")).execute();
  }

  @Test
  public void registerMetric_rethrowsException() throws Exception {
    ByteArrayInputStream inputStream = new ByteArrayInputStream("".getBytes(UTF_8));
    HttpResponse response = GoogleJsonResponseExceptionHelper.createHttpResponse(400, inputStream);
    HttpResponseException.Builder httpResponseExceptionBuilder =
        new HttpResponseException.Builder(response);
    httpResponseExceptionBuilder.setStatusCode(404);
    GoogleJsonResponseException exception =
        new GoogleJsonResponseException(httpResponseExceptionBuilder, null);
    when(metricDescriptorCreate.execute()).thenThrow(exception);
    StackdriverWriter writer =
        new StackdriverWriter(client, PROJECT, MONITORED_RESOURCE, MAX_QPS, MAX_POINTS_PER_REQUEST);

    try {
      writer.registerMetric(metric);
      fail("Expected GoogleJsonResponseException");
    } catch (GoogleJsonResponseException expected) {
      assertThat(exception.getStatusCode()).isEqualTo(404);
    }
  }

  @Test
  public void getEncodedTimeSeries_nullLabels_encodes() throws Exception {
    ByteArrayInputStream inputStream = new ByteArrayInputStream("".getBytes(UTF_8));
    HttpResponse response = GoogleJsonResponseExceptionHelper.createHttpResponse(400, inputStream);
    HttpResponseException.Builder httpResponseExceptionBuilder =
        new HttpResponseException.Builder(response);
    httpResponseExceptionBuilder.setStatusCode(400);
    httpResponseExceptionBuilder.setStatusMessage("ALREADY_EXISTS");
    GoogleJsonResponseException exception =
        new GoogleJsonResponseException(httpResponseExceptionBuilder, null);
    when(metricDescriptorCreate.execute()).thenThrow(exception);
    when(metricDescriptorGet.execute())
        .thenReturn(new MetricDescriptor().setName("foo").setLabels(null));
    StackdriverWriter writer =
        new StackdriverWriter(client, PROJECT, MONITORED_RESOURCE, MAX_QPS, MAX_POINTS_PER_REQUEST);
    writer.registerMetric(metric);

    TimeSeries timeSeries =
        writer.getEncodedTimeSeries(
            MetricPoint.create(metric, ImmutableList.of("foo"), new Instant(1337), 10L));

    assertThat(timeSeries.getMetric().getLabels()).isEmpty();
  }

  @Test
  public void createMetricDescriptor_simpleMetric_encodes() {
    MetricDescriptor descriptor = StackdriverWriter.createMetricDescriptor(metric);

    assertThat(descriptor.getType()).isEqualTo("custom.googleapis.com/name");
    assertThat(descriptor.getValueType()).isEqualTo("INT64");
    assertThat(descriptor.getDescription()).isEqualTo("desc");
    assertThat(descriptor.getDisplayName()).isEqualTo("vdn");
    assertThat(descriptor.getLabels())
        .containsExactly(
            new com.google.api.services.monitoring.v3.model.LabelDescriptor()
                .setValueType("STRING")
                .setKey("label")
                .setDescription("description"));
  }

  @Test
  public void createLabelDescriptors_simpleLabels_encodes() {
    ImmutableSet<LabelDescriptor> descriptors =
        ImmutableSet.of(
            LabelDescriptor.create("label1", "description1"),
            LabelDescriptor.create("label2", "description2"));

    ImmutableList<com.google.api.services.monitoring.v3.model.LabelDescriptor> encodedDescritors =
        StackdriverWriter.createLabelDescriptors(descriptors);

    assertThat(encodedDescritors)
        .containsExactly(
            new com.google.api.services.monitoring.v3.model.LabelDescriptor()
                .setValueType("STRING")
                .setKey("label1")
                .setDescription("description1"),
            new com.google.api.services.monitoring.v3.model.LabelDescriptor()
                .setValueType("STRING")
                .setKey("label2")
                .setDescription("description2"));
  }

  @Test
  public void getEncodedTimeSeries_simplePoint_encodes() throws Exception {
    StackdriverWriter writer =
        new StackdriverWriter(client, PROJECT, MONITORED_RESOURCE, MAX_QPS, MAX_POINTS_PER_REQUEST);
    MetricPoint<Long> nativePoint =
        MetricPoint.create(
            metric, ImmutableList.of("foo"), new Instant(1336), new Instant(1337), 10L);

    TimeSeries timeSeries = writer.getEncodedTimeSeries(nativePoint);

    assertThat(timeSeries.getValueType()).isEqualTo("INT64");
    assertThat(timeSeries.getMetricKind()).isEqualTo("CUMULATIVE");
    List<Point> points = timeSeries.getPoints();
    assertThat(points).hasSize(1);
    Point point = points.get(0);
    assertThat(point.getValue().getInt64Value()).isEqualTo(10L);
    assertThat(point.getInterval().getEndTime()).isEqualTo("1970-01-01T00:00:01.337Z");
    assertThat(point.getInterval().getStartTime()).isEqualTo("1970-01-01T00:00:01.336Z");
  }

  @Test
  public void getEncodedTimeSeries_booleanMetric_encodes() throws Exception {
    StackdriverWriter writer =
        new StackdriverWriter(client, PROJECT, MONITORED_RESOURCE, MAX_QPS, MAX_POINTS_PER_REQUEST);
    Metric<Boolean> boolMetric =
        new StoredMetric<>(
            "/name",
            "desc",
            "vdn",
            ImmutableSet.of(LabelDescriptor.create("label", "description")),
            Boolean.class);
    MetricDescriptor boolDescriptor = StackdriverWriter.createMetricDescriptor(boolMetric);
    when(metricDescriptorCreate.execute()).thenReturn(boolDescriptor);
    MetricPoint<Boolean> nativePoint =
        MetricPoint.create(boolMetric, ImmutableList.of("foo"), new Instant(1337), true);

    TimeSeries timeSeries = writer.getEncodedTimeSeries(nativePoint);

    assertThat(timeSeries.getValueType()).isEqualTo("BOOL");
    assertThat(timeSeries.getMetricKind()).isEqualTo("GAUGE");
    List<Point> points = timeSeries.getPoints();
    assertThat(points).hasSize(1);
    Point point = points.get(0);
    assertThat(point.getValue().getBoolValue()).isEqualTo(true);
    assertThat(point.getInterval().getEndTime()).isEqualTo("1970-01-01T00:00:01.337Z");
    assertThat(point.getInterval().getStartTime()).isEqualTo("1970-01-01T00:00:01.337Z");
  }
}
