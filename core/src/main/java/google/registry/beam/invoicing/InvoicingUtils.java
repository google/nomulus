// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.invoicing;

import static google.registry.beam.BeamUtils.getQueryFromFile;

import google.registry.util.SqlTemplate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import org.apache.beam.sdk.io.DefaultFilenamePolicy.Params;
import org.apache.beam.sdk.io.FileBasedSink;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.NestedValueProvider;
import org.apache.beam.sdk.transforms.SerializableFunction;

/** Pipeline helper functions used to generate invoices from instances of {@link BillingEvent}. */
public class InvoicingUtils {

  private InvoicingUtils() {}

  private static final DateTimeFormatter TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

  /**
   * Returns a function mapping from {@code BillingEvent} to filename {@code Params}.
   *
   * <p>Beam uses this to determine which file a given {@code BillingEvent} should get placed into.
   *
   * @param outputBucket the GCS bucket we're outputting reports to
   * @param yearMonthProvider a runtime provider for the yyyy-MM we're generating the invoice for
   */
  static SerializableFunction<BillingEvent, Params> makeDestinationFunction(
      String outputBucket, ValueProvider<String> yearMonthProvider) {
    return billingEvent ->
        new Params()
            .withShardTemplate("")
            .withSuffix(".csv")
            .withBaseFilename(
                NestedValueProvider.of(
                    yearMonthProvider,
                    yearMonth ->
                        FileBasedSink.convertToFileResourceIfPossible(
                            String.format(
                                "%s/%s/%s",
                                outputBucket, yearMonth, billingEvent.toFilename(yearMonth)))));
  }

  /**
   * Returns the default filename parameters for an unmappable {@code BillingEvent}.
   *
   * <p>The "failed" file should only be populated when an error occurs, which warrants further
   * investigation.
   */
  static Params makeEmptyDestinationParams(String outputBucket) {
    return new Params()
        .withBaseFilename(
            FileBasedSink.convertToFileResourceIfPossible(
                String.format("%s/%s", outputBucket, "FAILURES")));
  }

  /**
   * Returns a provider that creates a Bigquery query for a given project and yearMonth at runtime.
   *
   * <p>We only know yearMonth at runtime, so this provider fills in the {@code
   * sql/billing_events.sql} template at runtime.
   *
   * @param yearMonthProvider a runtime provider that returns which month we're invoicing for.
   * @param projectId the projectId we're generating invoicing for.
   */
  static ValueProvider<String> makeQueryProvider(
      ValueProvider<String> yearMonthProvider, String projectId) {
    return NestedValueProvider.of(
        yearMonthProvider,
        (yearMonth) -> {
          // Get the timestamp endpoints capturing the entire month with microsecond precision
          YearMonth reportingMonth = YearMonth.parse(yearMonth);
          LocalDateTime firstMoment = reportingMonth.atDay(1).atTime(LocalTime.MIDNIGHT);
          LocalDateTime lastMoment = reportingMonth.atEndOfMonth().atTime(LocalTime.MAX);
          // Construct the month's query by filling in the billing_events.sql template
          return SqlTemplate.create(getQueryFromFile(InvoicingPipeline.class, "billing_events.sql"))
              .put("FIRST_TIMESTAMP_OF_MONTH", firstMoment.format(TIMESTAMP_FORMATTER))
              .put("LAST_TIMESTAMP_OF_MONTH", lastMoment.format(TIMESTAMP_FORMATTER))
              .put("PROJECT_ID", projectId)
              .put("DATASTORE_EXPORT_DATA_SET", "latest_datastore_export")
              .put("ONETIME_TABLE", "OneTime")
              .put("REGISTRY_TABLE", "Registry")
              .put("REGISTRAR_TABLE", "Registrar")
              .put("CANCELLATION_TABLE", "Cancellation")
              .build();
        });
  }
}
