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

package google.registry.dns.writer.dnsupdate;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dagger.multibindings.StringKey;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.writer.DnsWriter;
import jakarta.inject.Named;
import java.net.UnknownHostException;
import java.time.Duration;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SimpleResolver;

/** Dagger module that provides a DnsUpdateWriter. */
@Module
public abstract class DnsUpdateWriterModule {

  @Provides
  static Resolver provideResolver(
      @Config("dnsUpdateHost") String updateHost,
      @Config("dnsUpdateTimeout") Duration updateTimeout) {
    try {
      SimpleResolver resolver = new SimpleResolver(updateHost);
      resolver.setTCP(true);
      resolver.setTimeout(updateTimeout);
      return resolver;
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
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
