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

package google.registry.reporting;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;

import com.google.api.services.dataflow.Dataflow;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.DefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.Parameter;
import google.registry.util.Clock;
import google.registry.util.GoogleCredentialsBundle;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.joda.time.YearMonth;
import org.joda.time.format.ISODateTimeFormat;

/** Dagger module for injecting common settings for all reporting tasks. */
@Module
public class ReportingModule {

  public static final String BEAM_QUEUE = "beam-reporting";

  /**
   * The request parameter name used by reporting actions that takes a year/month parameter, which
   * defaults to the last month.
   */
  // TODO(b/120497263): remove this and replace with the date
  public static final String PARAM_YEAR_MONTH = "yearMonth";

  /**
   * The request parameter name used by reporting actions that take a local date as a parameter,
   * which defaults to the current date.
   */
  public static final String PARAM_DATE = "date";

  /** The request parameter specifying the jobId for a running Dataflow pipeline. */
  public static final String PARAM_JOB_ID = "jobId";

  /** The request parameter for specifying which database reporting actions should read from. */
  public static final String DATABASE = "database";

  /** Provides the Cloud Dataflow jobId for a pipeline. */
  @Provides
  @Parameter(PARAM_JOB_ID)
  static String provideJobId(HttpServletRequest req) {
    return extractRequiredParameter(req, PARAM_JOB_ID);
  }

  /** Provides the database for the pipeline to read from. */
  @Provides
  @Parameter(DATABASE)
  static String provideDatabase(HttpServletRequest req) {
    Optional<String> optionalDatabase = extractOptionalParameter(req, DATABASE);
    return optionalDatabase.orElse(tm().isOfy() ? "DATASTORE" : "CLOUD_SQL");
  }

  /** Extracts an optional YearMonth in yyyy-MM format from the request. */
  @Provides
  @Parameter(PARAM_YEAR_MONTH)
  static Optional<YearMonth> provideYearMonthOptional(HttpServletRequest req) {
    Optional<String> optionalYearMonthStr = extractOptionalParameter(req, PARAM_YEAR_MONTH);
    try {
      return optionalYearMonthStr.map(s -> YearMonth.parse(s, ISODateTimeFormat.yearMonth()));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(
          String.format(
              "yearMonth must be in yyyy-MM format, got %s instead",
              optionalYearMonthStr.orElse("UNSPECIFIED YEARMONTH")));
    }
  }

  /**
   * Provides the yearMonth in yyyy-MM format, if not specified in the request, defaults to one
   * month prior to run time.
   */
  @Provides
  static YearMonth provideYearMonth(
      @Parameter(PARAM_YEAR_MONTH) Optional<YearMonth> yearMonthOptional,
      @Parameter(PARAM_DATE) LocalDate date) {
    return yearMonthOptional.orElseGet(() -> new YearMonth(date.minusMonths(1)));
  }

  /** Extracts an optional date in yyyy-MM-dd format from the request. */
  @Provides
  @Parameter(PARAM_DATE)
  static Optional<LocalDate> provideDateOptional(HttpServletRequest req) {
    Optional<String> optionalDateString = extractOptionalParameter(req, PARAM_DATE);
    try {
      return optionalDateString.map(s -> LocalDate.parse(s, ISODateTimeFormat.yearMonthDay()));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(
          String.format(
              "date must be in yyyy-MM-dd format, got %s instead",
              optionalDateString.orElse("UNSPECIFIED LOCAL DATE")),
          e);
    }
  }

  /**
   * Provides the local date in yyyy-MM-dd format, if not specified in the request, defaults to the
   * current date.
   */
  @Provides
  @Parameter(PARAM_DATE)
  static LocalDate provideDate(HttpServletRequest req, Clock clock) {
    return provideDateOptional(req)
        .orElseGet(() -> new LocalDate(clock.nowUtc(), DateTimeZone.UTC));
  }

  /** Constructs a {@link Dataflow} API client with default settings. */
  @Provides
  static Dataflow provideDataflow(
      @DefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("projectId") String projectId) {
    return new Dataflow.Builder(
            credentialsBundle.getHttpTransport(),
            credentialsBundle.getJsonFactory(),
            credentialsBundle.getHttpRequestInitializer())
        .setApplicationName(String.format("%s billing", projectId))
        .build();
  }
}
