// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.whois;

import static com.google.common.base.Preconditions.checkState;
import static com.google.monitoring.metrics.EventMetric.DEFAULT_FITTER;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.monitoring.metrics.EventMetric;
import com.google.monitoring.metrics.IncrementableMetric;
import com.google.monitoring.metrics.LabelDescriptor;
import com.google.monitoring.metrics.MetricRegistryImpl;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.util.Optional;
import org.joda.time.DateTime;

/**
 * Instrumentation for WHOIS requests.
 *
 * @see WhoisAction
 */
public class WhoisMetrics {

  private static final ImmutableSet<LabelDescriptor> LABEL_DESCRIPTORS =
      ImmutableSet.of(
          LabelDescriptor.create("command_name", "The name of the WHOIS command."),
          LabelDescriptor.create(
              "num_results", "The number of results returned by the WHOIS command."),
          LabelDescriptor.create("status", "The return status of the WHOIS command."));

  private static final IncrementableMetric whoisRequests =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/whois/requests", "Count of WHOIS Requests", "count", LABEL_DESCRIPTORS);

  private static final EventMetric processingTime =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/whois/processing_time",
              "WHOIS Processing Time",
              "milliseconds",
              LABEL_DESCRIPTORS,
              DEFAULT_FITTER);

  @Inject
  public WhoisMetrics() {}

  /** Records the given {@link WhoisMetric} and its associated processing time. */
  public void recordWhoisMetric(WhoisMetric metric) {
    whoisRequests.increment(
        metric.commandName().orElse(""),
        Integer.toString(metric.numResults()),
        Integer.toString(metric.status()));
    processingTime.record(
        metric.endTimestamp().getMillis() - metric.startTimestamp().getMillis(),
        metric.commandName().orElse(""),
        Integer.toString(metric.numResults()),
        Integer.toString(metric.status()));
  }

  /** A value class for recording attributes of a WHOIS metric. */
  public record WhoisMetric(
      Optional<String> commandName,
      int numResults,
      int status,
      DateTime startTimestamp,
      DateTime endTimestamp) {

    /**
     * Create a {@link WhoisMetric.Builder} for a request context, with the start and end timestamps
     * taken from the given clock.
     *
     * <p>The start timestamp is recorded now, and the end timestamp at {@code build()}.
     */
    public static WhoisMetric.Builder builderForRequest(Clock clock) {
      return WhoisMetric.builder().setStartTimestamp(clock.nowUtc()).setClock(clock);
    }

    /** Create a {@link WhoisMetric.Builder}. */
    public static Builder builder() {
      return new AutoBuilder_WhoisMetrics_WhoisMetric_Builder();
    }

    /** A builder to create instances of {@link WhoisMetric}. */
    @AutoBuilder
    public abstract static class Builder {

      boolean wasBuilt = false;

      /** Builder-only clock to support automatic recording of endTimestamp on {@link #build()}. */
      private Clock clock = null;

      public Builder setCommand(WhoisCommand command) {
        // All WHOIS command class names share the "Command" suffix, so strip it out in order to
        // have shorter labels.
        return setCommandName(command.getClass().getSimpleName().replaceFirst("Command$", ""));
      }

      public abstract Builder setCommandName(String commandName);

      public abstract Builder setNumResults(int numResults);

      public abstract Builder setStatus(int status);

      abstract Builder setStartTimestamp(DateTime startTimestamp);

      abstract Builder setEndTimestamp(DateTime endTimestamp);

      Builder setClock(Clock clock) {
        this.clock = clock;
        return this;
      }

      /**
       * Build an instance of {@link WhoisMetric} using this builder.
       *
       * <p>If a clock was provided with {@code setClock()}, the end timestamp will be set to the
       * current timestamp of the clock; otherwise end timestamp must have been previously set.
       */
      public WhoisMetric build() {
        checkState(
            !wasBuilt, "WhoisMetric was already built; building it again is most likely an error");
        if (clock != null) {
          setEndTimestamp(clock.nowUtc());
        }
        wasBuilt = true;
        return autoBuild();
      }

      abstract WhoisMetric autoBuild();
    }
  }
}
