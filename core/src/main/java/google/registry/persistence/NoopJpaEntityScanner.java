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

import com.google.common.collect.ImmutableSet;
import org.hibernate.boot.archive.internal.StandardArchiveDescriptorFactory;
import org.hibernate.boot.archive.scan.internal.ScanResultImpl;
import org.hibernate.boot.archive.scan.internal.StandardScanner;
import org.hibernate.boot.archive.scan.spi.ScanEnvironment;
import org.hibernate.boot.archive.scan.spi.ScanOptions;
import org.hibernate.boot.archive.scan.spi.ScanParameters;
import org.hibernate.boot.archive.scan.spi.ScanResult;
import org.hibernate.boot.archive.scan.spi.Scanner;

/**
 * A do-nothing {@link Scanner} for Hibernate that works around bugs in Hibernate's default
 * implementation. This is required for the Nomulus tool.
 *
 * <p>Please refer to <a href="../../../../resources/META-INF/persistence.xml">persistence.xml</a>
 * for more information.
 */
public class NoopJpaEntityScanner extends StandardScanner {

  public NoopJpaEntityScanner() {
    super(StandardArchiveDescriptorFactory.INSTANCE);
  }

  @Override
  public ScanResult scan(
      ScanEnvironment environment, ScanOptions options, ScanParameters parameters) {
    return new ScanResultImpl(ImmutableSet.of(), ImmutableSet.of(), ImmutableSet.of());
  }
}
