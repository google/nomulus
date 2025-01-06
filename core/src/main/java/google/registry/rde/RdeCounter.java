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

package google.registry.rde;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import google.registry.model.rde.RdeMode;
import google.registry.xjc.rde.XjcRdeDepositTypeType;
import google.registry.xjc.rdeheader.XjcRdeHeader;
import google.registry.xjc.rdeheader.XjcRdeHeaderCount;
import google.registry.xjc.rdeheader.XjcRdeHeaderElement;
import google.registry.xjc.rdereport.XjcRdeReport;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** Utility class for generating a single {@link XjcRdeHeader} while marshalling a deposit. */
@NotThreadSafe
public final class RdeCounter {

  private static final String URI_ESCROW = "draft-arias-noguchi-registry-data-escrow-06";
  private static final String URI_MAPPING = "draft-arias-noguchi-dnrd-objects-mapping-05";
  private static final int ICANN_REPORT_SPEC_VERSION = 1;

  private final EnumMap<RdeResourceType, AtomicLong> counts = new EnumMap<>(RdeResourceType.class);

  @Inject
  public RdeCounter() {
    NON_HEADER_RDE_RESOURCE_TYPES.forEach(type -> counts.put(type, new AtomicLong()));
  }

  /** Increment the count on a given resource. */
  public void increment(RdeResourceType type) {
    counts.get(type).incrementAndGet();
  }

  /** Constructs a header containing the sum of {@link #increment(RdeResourceType)} calls. */
  public XjcRdeHeader makeHeader(String tld, RdeMode mode) {
    XjcRdeHeader header = new XjcRdeHeader();
    header.setTld(tld);
    NON_HEADER_RDE_RESOURCE_TYPES
        .stream()
        .filter(type -> type.getModes().contains(mode))
        .forEach(type -> header.getCounts().add(makeCount(type.getUri(), counts.get(type).get())));
    return header;
  }

  /** Returns an ICANN notification report as a JAXB object. */
  public XjcRdeReport
      makeReport(String id, DateTime watermark, XjcRdeHeader header, int revision) {
    XjcRdeReport report = new XjcRdeReport();
    report.setId(id);
    report.setKind(XjcRdeDepositTypeType.FULL);
    report.setCrDate(watermark);
    report.setWatermark(watermark);
    report.setVersion(ICANN_REPORT_SPEC_VERSION);
    report.setRydeSpecEscrow(URI_ESCROW);
    report.setRydeSpecMapping(URI_MAPPING);
    report.setResend(revision);
    report.setHeader(new XjcRdeHeaderElement(header));
    return report;
  }

  private static final ImmutableSet<RdeResourceType> NON_HEADER_RDE_RESOURCE_TYPES =
      Sets.difference(EnumSet.allOf(RdeResourceType.class), ImmutableSet.of(RdeResourceType.HEADER))
          .immutableCopy();

  private static XjcRdeHeaderCount makeCount(String uri, long count) {
    XjcRdeHeaderCount bean = new XjcRdeHeaderCount();
    bean.setUri(uri);
    bean.setValue(count);
    return bean;
  }
}
