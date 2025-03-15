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

package google.registry.tools;

import com.beust.jcommander.ParametersDelegate;
import google.registry.bigquery.BigqueryConnection;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

/** A {@link Command} that uses the bigquery client API. */
abstract class BigqueryCommand implements Command {

  /** Parameter delegate for encapsulating flags needed to set up the {@link BigqueryConnection}. */
  // Do not make this final - compile-time constant inlining may interfere with JCommander.
  @ParametersDelegate
  private BigqueryParameters bigqueryParameters = new BigqueryParameters();

  /** Connection object for interacting with the Bigquery API. */
  private BigqueryConnection bigquery;

  @Inject Provider<BigqueryConnection.Builder> bigQueryConnectionBuilderProvider;

  @Override
  public void run() throws Exception {
    try (BigqueryConnection autoClosingBigquery =
        bigqueryParameters.newConnection(bigQueryConnectionBuilderProvider.get())) {
      bigquery = autoClosingBigquery;
      runWithBigquery();
    }
  }

  /** Returns the {@link BigqueryConnection} object that has been initialized for use. */
  BigqueryConnection bigquery() {
    return bigquery;
  }

  /** Subclasses must override this to define command behavior. */
  abstract void runWithBigquery() throws Exception;
}
