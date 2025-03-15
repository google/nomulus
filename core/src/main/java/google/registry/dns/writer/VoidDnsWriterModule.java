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

package google.registry.dns.writer;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dagger.multibindings.StringKey;
import jakarta.inject.Named;

/** Dagger module that disables DNS updates. */
@Module
public abstract class VoidDnsWriterModule {

  @Binds
  @IntoMap
  @StringKey(VoidDnsWriter.NAME)
  abstract DnsWriter provideWriter(VoidDnsWriter writer);

  @Provides
  @IntoSet
  @Named("dnsWriterNames")
  static String provideWriterName() {
    return VoidDnsWriter.NAME;
  }
}
