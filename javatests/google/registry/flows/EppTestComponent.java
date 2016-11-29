// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.flows;

import static org.mockito.Mockito.mock;

import com.google.appengine.api.modules.ModulesService;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.Subcomponent;
import google.registry.config.ConfigModule;
import google.registry.dns.DnsQueue;
import google.registry.flows.custom.CustomLogicFactoryModule;
import google.registry.monitoring.whitebox.BigQueryMetricsEnqueuer;
import google.registry.monitoring.whitebox.EppMetric;
import google.registry.request.RequestScope;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.util.Clock;
import google.registry.util.Sleeper;
import javax.inject.Singleton;

/** Dagger component for running EPP tests. */
@Singleton
@Component(
    modules = {
        ConfigModule.class,
        CustomLogicFactoryModule.class,
        EppTestComponent.FakesAndMocksModule.class
    })
interface EppTestComponent {

  RequestComponent startRequest();

  /** Module for injecting fakes and mocks. */
  @Module
  static class FakesAndMocksModule {

    final FakeClock clock;
    final Sleeper sleeper;
    final DnsQueue dnsQueue;
    final BigQueryMetricsEnqueuer metricsEnqueuer;
    final EppMetric.Builder metricBuilder;
    final ModulesService modulesService;

    FakesAndMocksModule(FakeClock clock) {
      this.clock = clock;
      this.sleeper = new FakeSleeper(clock);
      this.dnsQueue = DnsQueue.create();
      this.metricBuilder = EppMetric.builderForRequest("request-id-1", clock);
      this.modulesService = mock(ModulesService.class);
      this.metricsEnqueuer = mock(BigQueryMetricsEnqueuer.class);
    }

    @Provides
    Clock provideClock() {
      return clock;
    }

    @Provides
    Sleeper provideSleeper() {
      return sleeper;
    }

    @Provides
    DnsQueue provideDnsQueue() {
      return dnsQueue;
    }

    @Provides
    EppMetric.Builder provideMetrics() {
      return metricBuilder;
    }

    @Provides
    ModulesService provideModulesService() {
      return modulesService;
    }

    @Provides
    BigQueryMetricsEnqueuer provideBigQueryMetricsEnqueuer() {
      return metricsEnqueuer;
    }
  }

  /** Subcomponent for request scoped injections. */
  @RequestScope
  @Subcomponent
  interface RequestComponent {
    EppController eppController();
    FlowComponent.Builder flowComponentBuilder();
  }
}

