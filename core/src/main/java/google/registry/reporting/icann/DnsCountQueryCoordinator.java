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

package google.registry.reporting.icann;

import google.registry.bigquery.BigqueryConnection;
import org.joda.time.YearMonth;

/**
 * Methods for preparing and querying DNS statistics.
 *
 * <p>DNS systems may have different ways of providing this information, so it's useful to
 * modularize this.
 *
 * <p>Derived classes must provide a constructor that accepts a
 * {@link google.registry.reporting.icann.DnsCountQueryCoordinator.Params}.  To override this,
 * define dnsCountQueryCoordinatorClass in your config file.
 */
public interface DnsCountQueryCoordinator {

  /**
   * Class to carry parameters for a new coordinator.
   *
   * <p>If your report query requires any additional parameters, add them here.
   */
  class Params {

    public BigqueryConnection bigquery;

    /** The Google Cloud project id. */
    public String projectId;

    /** The BigQuery dataset from which to query. */
    public String icannReportingDataSet;

    public Params(BigqueryConnection bigquery, String projectId, String icannReportingDataSet) {
      this.bigquery = bigquery;
      this.projectId = projectId;
      this.icannReportingDataSet = icannReportingDataSet;
    }
  }

  /** Creates the string used to query bigtable for DNS count information. */
  String createQuery(YearMonth yearMonth);

  /**
   * Do any necessary preparation for the DNS query.
   *
   * <p>This potentially throws {@link InterruptedException} because some implementations use
   * interruptible futures to prepare the query (and the correct thing to do with such exceptions is
   * to handle them correctly or propagate them as-is, no {@link RuntimeException} wrapping).
   */
  void prepareForQuery(YearMonth yearMonth) throws InterruptedException;
}
