// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.reporting.billing;

import static google.registry.request.RequestParameters.extractOptionalBooleanParameter;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import javax.inject.Qualifier;
import org.joda.time.YearMonth;

/** Module for dependencies required by monthly billing actions. */
@Module
public final class BillingModule {

  public static final String DETAIL_REPORT_PREFIX = "invoice_details";
  public static final String INVOICES_DIRECTORY = "invoices";

  static final String PARAM_SHOULD_PUBLISH = "shouldPublish";
  static final String CRON_QUEUE = "retryable-cron-tasks";


  @Provides
  @Parameter(PARAM_SHOULD_PUBLISH)
  static boolean provideShouldPublish(
      HttpServletRequest req,
      @Config("defaultShouldPublishInvoices") boolean defaultShouldPublishInvoices) {
    return extractOptionalBooleanParameter(req, PARAM_SHOULD_PUBLISH)
        .orElse(defaultShouldPublishInvoices);
  }

  @Provides
  @InvoiceDirectoryPrefix
  static String provideDirectoryPrefix(YearMonth yearMonth) {
    return String.format("%s/%s/", INVOICES_DIRECTORY, yearMonth.toString());
  }

  /** Dagger qualifier for the subdirectory we stage to/upload from for invoices. */
  @Qualifier
  @Documented
  @Retention(RUNTIME)
  @interface InvoiceDirectoryPrefix{}
}
