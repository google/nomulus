// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence;

import org.hibernate.boot.archive.internal.StandardArchiveDescriptorFactory;
import org.hibernate.boot.archive.scan.internal.NoopEntryHandler;
import org.hibernate.boot.archive.scan.internal.ScanResultCollector;
import org.hibernate.boot.archive.scan.internal.StandardScanner;
import org.hibernate.boot.archive.scan.spi.AbstractScannerImpl;
import org.hibernate.boot.archive.scan.spi.ScanEnvironment;
import org.hibernate.boot.archive.scan.spi.ScanOptions;
import org.hibernate.boot.archive.scan.spi.ScanParameters;
import org.hibernate.boot.archive.scan.spi.ScanResult;
import org.hibernate.boot.archive.spi.ArchiveDescriptor;
import org.hibernate.boot.archive.spi.ArchiveEntry;
import org.hibernate.boot.archive.spi.ArchiveEntryHandler;

/**
 * Custom auto-detector of JPA entities for Hibernate that works around bugs in Hibernate's {@link
 * StandardScanner}. This is required for the Nomulus tool.
 *
 * <p>With entity auto-detection enabled, Hibernate scans every class in the <em>root</em> module
 * (the one that contains persistence.xml). In the Nomulus tool, which is released as a single jar,
 * every third-party class is scanned too. This not only causes longer JPA setup time, it also
 * exposes bugs in the Hibernate scanner. For example, a repackaged Guava class ( {@code
 * com.google.appengine.repackaged.com.google.common.html.LinkDetector}) in
 * appengine-api-1.0-sdk:1.9.81 can break the scanner in hibernate-core:5.4.17.
 *
 * <p>In the Nomulus project, all JPA entities are under the "google/registry" hierarchy and in the
 * root module. This class takes advantage of this fact and narrows down the scanning scope, and
 * works around the Hibernate bugs.
 *
 * <p>Please refer to <a href="../../../../resources/META-INF/persistence.xml">persistence.xml</a>
 * for more information.
 */
public class JpaEntityScanner extends StandardScanner {

  private static final String ENTITY_PREFIX = "google/registry/";
  private static final String MAPPING_PREFIX = "META-INF/";

  public JpaEntityScanner() {
    super(StandardArchiveDescriptorFactory.INSTANCE);
  }

  @Override
  public ScanResult scan(
      ScanEnvironment environment, ScanOptions options, ScanParameters parameters) {
    final ScanResultCollector collector = new ScanResultCollector(environment, options, parameters);

    if (environment.getRootUrl() != null) {
      final ScanContext context = new ScanContext(true, collector);
      final ArchiveDescriptor descriptor =
          StandardArchiveDescriptorFactory.INSTANCE.buildArchiveDescriptor(
              environment.getRootUrl());
      descriptor.visitArchive(context);
    }

    return collector.toScanResult();
  }

  private static class ScanContext extends AbstractScannerImpl.ArchiveContextImpl {

    public ScanContext(boolean isRootUrl, ScanResultCollector scanResultCollector) {
      super(isRootUrl, scanResultCollector);
    }

    @Override
    public ArchiveEntryHandler obtainArchiveEntryHandler(ArchiveEntry entry) {
      final String nameWithinArchive = entry.getNameWithinArchive();
      if (nameWithinArchive.startsWith(ENTITY_PREFIX)
          || nameWithinArchive.startsWith(MAPPING_PREFIX)) {
        return super.obtainArchiveEntryHandler(entry);
      }
      return NoopEntryHandler.NOOP_INSTANCE;
    }
  }
}
