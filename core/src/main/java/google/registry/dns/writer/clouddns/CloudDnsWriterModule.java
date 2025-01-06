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

package google.registry.dns.writer.clouddns;

import com.google.api.services.dns.Dns;
import com.google.common.util.concurrent.RateLimiter;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dagger.multibindings.StringKey;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.writer.DnsWriter;
import google.registry.util.GoogleCredentialsBundle;
import java.util.Optional;
import javax.inject.Named;

/** Dagger module for Google Cloud DNS service connection objects. */
@Module
public abstract class CloudDnsWriterModule {

  @Provides
  static Dns provideDns(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("projectId") String projectId,
      @Config("cloudDnsRootUrl") Optional<String> rootUrl,
      @Config("cloudDnsServicePath") Optional<String> servicePath) {
    Dns.Builder builder =
        new Dns.Builder(
                credentialsBundle.getHttpTransport(),
                credentialsBundle.getJsonFactory(),
                credentialsBundle.getHttpRequestInitializer())
            .setApplicationName(projectId);

    rootUrl.ifPresent(builder::setRootUrl);
    servicePath.ifPresent(builder::setServicePath);

    return builder.build();
  }

  @Binds
  @IntoMap
  @StringKey(CloudDnsWriter.NAME)
  abstract DnsWriter provideWriter(CloudDnsWriter writer);

  @Provides
  @IntoSet
  @Named("dnsWriterNames")
  static String provideWriterName() {
    return CloudDnsWriter.NAME;
  }

  @Provides
  @Named("cloudDns")
  static RateLimiter provideRateLimiter() {
    // This is the default max QPS for Cloud DNS. It can be increased by contacting the team
    // via the Quotas page on the Cloud Console.
    int cloudDnsMaxQps = 50;
    return RateLimiter.create(cloudDnsMaxQps);
  }

  @Provides
  @Named("cloudDnsNumThreads")
  static int provideNumThreads() {
    // TODO(b/70217860): find the "best" number of threads, taking into account running time, App
    // Engine constraints, and any Cloud DNS comsiderations etc.
    //
    // NOTE: any number below 2 will not use threading at all.
    return 10;
  }

  private CloudDnsWriterModule() {}
}
