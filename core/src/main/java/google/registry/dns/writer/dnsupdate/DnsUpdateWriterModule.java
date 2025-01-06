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

package google.registry.dns.writer.dnsupdate;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dagger.multibindings.StringKey;
import google.registry.dns.writer.DnsWriter;
import javax.inject.Named;
import javax.net.SocketFactory;

/** Dagger module that provides a DnsUpdateWriter. */
@Module
public abstract class DnsUpdateWriterModule {

  @Provides
  static SocketFactory provideSocketFactory() {
    return SocketFactory.getDefault();
  }

  @Binds
  @IntoMap
  @StringKey(DnsUpdateWriter.NAME)
  abstract DnsWriter provideWriter(DnsUpdateWriter writer);

  @Provides
  @IntoSet
  @Named("dnsWriterNames")
  static String provideWriterName() {
    return DnsUpdateWriter.NAME;
  }
}
