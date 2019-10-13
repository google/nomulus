// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.proxy.metric;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.monitoring.metrics.CustomFitter;
import com.google.monitoring.metrics.ExponentialFitter;
import com.google.monitoring.metrics.FibonacciFitter;
import com.google.monitoring.metrics.LabelDescriptor;

/** Base class for metrics. */
public abstract class AbstractMetrics {

  /**
   * Labels to register metrics with.
   *
   * <p>The client certificate hash value is only used for EPP metrics. For WHOIS metrics, it will
   * always be {@code "none"}. In order to get the actual registrar name, one can use the {@code
   * nomulus} tool:
   *
   * <pre>
   * nomulus -e production list_registrars -f clientCertificateHash | grep $HASH
   * </pre>
   */
   protected static final ImmutableSet<LabelDescriptor> LABELS =
      ImmutableSet.of(
          LabelDescriptor.create("protocol", "Name of the protocol."),
          LabelDescriptor.create(
              "client_cert_hash", "SHA256 hash of the client certificate, if available."));

  // Maximum request size is defined in the config file, this is not realistic and we'd be out of
  // memory when the size approach 1 GB.
  protected static final CustomFitter DEFAULT_SIZE_FITTER = FibonacciFitter.create(1073741824);

  // Maximum 1 hour latency, this is not specified by the spec, but given we have a one hour idle
  // timeout, it seems reasonable that maximum latency is set to 1 hour as well. If we are
  // approaching anywhere near 1 hour latency, we'd be way out of SLO anyway.
  protected static final ExponentialFitter DEFAULT_LATENCY_FITTER =
      ExponentialFitter.create(22, 2, 1.0);

  /**
   * Resets all metrics.
   *
   * <p>This should only be used in tests to reset states. Production code should not call this
   * method.
   */
  @VisibleForTesting
  abstract void resetMetrics();
}
