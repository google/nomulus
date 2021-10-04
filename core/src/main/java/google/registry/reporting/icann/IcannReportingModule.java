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

package google.registry.reporting.icann;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;
import static google.registry.request.RequestParameters.extractSetOfEnumParameters;

import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import dagger.Module;
import dagger.Provides;
import google.registry.bigquery.BigqueryConnection;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.Parameter;
import java.util.Optional;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.Duration;

/** Module for dependencies required by ICANN monthly transactions/activity reporting. */
@Module
public final class IcannReportingModule {

  /** Enum determining the type of report to generate or upload. */
  public enum ReportType {
    TRANSACTIONS,
    ACTIVITY
  }

  static final String PARAM_SUBDIR = "subdir";
  static final String PARAM_REPORT_TYPES = "reportTypes";
  static final String ICANN_REPORTING_DATA_SET = "icannReportingDataSet";
  static final String DATASTORE_EXPORT_DATA_SET = "latest_datastore_export";
  static final String MANIFEST_FILE_NAME = "MANIFEST.txt";

  /** Provides an optional subdirectory to store/upload reports to, extracted from the request. */
  @Provides
  @Parameter(PARAM_SUBDIR)
  static Optional<String> provideSubdirOptional(HttpServletRequest req) {
    return extractOptionalParameter(req, PARAM_SUBDIR).map(IcannReportingModule::checkSubdirValid);
  }

  /** Provides the subdirectory to store/upload reports to, extracted from the request. */
  @Provides
  @Parameter(PARAM_SUBDIR)
  static String provideSubdir(HttpServletRequest req) {
    return checkSubdirValid(extractRequiredParameter(req, PARAM_SUBDIR));
  }

  static String checkSubdirValid(String subdir) {
    if (subdir.startsWith("/") || subdir.endsWith("/")) {
      throw new BadRequestException(
          String.format("subdir must not start or end with a \"/\", got %s instead.", subdir));
    }
    return subdir;
  }

  /** Provides an optional reportType to store/upload reports to, extracted from the request. */
  @Provides
  @Parameter(PARAM_REPORT_TYPES)
  static ImmutableSet<ReportType> provideReportTypes(HttpServletRequest req) {
    ImmutableSet<ReportType> reportTypes =
        extractSetOfEnumParameters(req, ReportType.class, PARAM_REPORT_TYPES);
    return reportTypes.isEmpty() ? ImmutableSet.copyOf(ReportType.values()) : reportTypes;
  }

  /**
   * Constructs a BigqueryConnection with default settings.
   *
   * <p>We use Bigquery to generate ICANN monthly reports via large aggregate SQL queries.
   *
   * @see ActivityReportingQueryBuilder
   * @see google.registry.tools.BigqueryParameters for justifications of defaults.
   */
  @Provides
  static BigqueryConnection provideBigqueryConnection(
      BigqueryConnection.Builder bigQueryConnectionBuilder,
      @Named(ICANN_REPORTING_DATA_SET) String icannReportingDataSet) {
    try {
      return bigQueryConnectionBuilder
          .setExecutorService(MoreExecutors.newDirectExecutorService())
          .setDatasetId(icannReportingDataSet)
          .setOverwrite(true)
          .setPollInterval(Duration.standardSeconds(1))
          .build();
    } catch (Throwable e) {
      throw new RuntimeException("Could not initialize BigqueryConnection!", e);
    }
  }

  @Provides
  @Named(ICANN_REPORTING_DATA_SET)
  static String provideIcannReportingDataSet() {
    return tm().isOfy() ? "icann_reporting" : "cloud_sql_icann_reporting";
  }
}
