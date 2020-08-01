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

package google.registry.proxy;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.proxy.ProxyConfig.Environment.LOCAL;
import static google.registry.proxy.ProxyConfig.getProxyConfig;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.networking.handler.SslClientInitializer;
import google.registry.networking.handler.SslServerInitializer;
import google.registry.networking.module.CertificateSupplierModule;
import google.registry.networking.module.CertificateSupplierModule.Mode;
import google.registry.proxy.EppProtocolModule.EppProtocol;
import google.registry.proxy.HealthCheckProtocolModule.HealthCheckProtocol;
import google.registry.proxy.HttpsRelayProtocolModule.HttpsRelayProtocol;
import google.registry.proxy.ProxyConfig.Environment;
import google.registry.proxy.WebWhoisProtocolsModule.HttpWhoisProtocol;
import google.registry.proxy.WhoisProtocolModule.WhoisProtocol;
import google.registry.proxy.handler.BackendMetricsHandler;
import google.registry.proxy.handler.FrontendMetricsHandler;
import google.registry.proxy.handler.ProxyProtocolHandler;
import google.registry.proxy.handler.QuotaHandler.EppQuotaHandler;
import google.registry.proxy.handler.QuotaHandler.WhoisQuotaHandler;
import google.registry.proxy.handler.RelayHandler.FullHttpRequestRelayHandler;
import google.registry.proxy.handler.RelayHandler.FullHttpResponseRelayHandler;
import google.registry.proxy.handler.WebWhoisRedirectHandler;
import google.registry.testing.FakeClock;
import google.registry.util.Clock;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for end-to-end tests of a {@link Protocol}.
 *
 * <p>The end-to-end tests ensures that the business logic that a {@link Protocol} defines are
 * correctly performed by various handlers attached to its pipeline. Non-business essential handlers
 * should be excluded.
 *
 * <p>Subclass should implement an no-arg constructor that calls constructors of this class,
 * providing the method reference of the {@link TestComponent} method to call to obtain the list of
 * {@link ChannelHandler} providers for the {@link Protocol} to test, and optionally a set of {@link
 * ChannelHandler} classes to exclude from testing.
 */
public abstract class ProtocolModuleTest {

  static final ProxyConfig PROXY_CONFIG = getProxyConfig(Environment.LOCAL);

  TestComponent testComponent;

  /**
   * Default list of handler classes that are not of interest in end-to-end testing of the {@link
   * Protocol}.
   */
  private static final ImmutableSet<Class<? extends ChannelHandler>> DEFAULT_EXCLUDED_HANDLERS =
      ImmutableSet.of(
          // The PROXY protocol is only used when the proxy is behind the GCP load balancer. It is
          // not part of any business logic.
          ProxyProtocolHandler.class,
          // SSL is part of the business logic for some protocol (EPP for example), but its
          // impact is isolated. Including it makes tests much more complicated. It should be tested
          // separately in its own unit tests.
          SslClientInitializer.class,
          SslServerInitializer.class,
          // These two handlers provide essential functionalities for the proxy to operate, but they
          // do not directly implement the business logic of a well-defined protocol. They should be
          // tested separately in their respective unit tests.
          FullHttpRequestRelayHandler.class,
          FullHttpResponseRelayHandler.class,
          // This handler is tested in its own unit tests. It is installed in web whois redirect
          // protocols. The end-to-end tests for the rest of the handlers in its pipeline need to
          // be able to emit incoming requests out of the channel for assertions. Therefore this
          // handler is removed from the pipeline.
          WebWhoisRedirectHandler.class,
          // The rest are not part of business logic and do not need to be tested, obviously.
          LoggingHandler.class,
          // Metrics instrumentation is tested separately.
          BackendMetricsHandler.class,
          FrontendMetricsHandler.class,
          // Quota management is tested separately.
          WhoisQuotaHandler.class,
          EppQuotaHandler.class,
          ReadTimeoutHandler.class);

  protected EmbeddedChannel channel;

  /**
   * Method reference to the component method that exposes the list of handler providers for the
   * specific {@link Protocol} in interest.
   */
  private final Function<TestComponent, ImmutableList<Provider<? extends ChannelHandler>>>
      handlerProvidersMethod;

  private final ImmutableSet<Class<? extends ChannelHandler>> excludedHandlers;

  protected ProtocolModuleTest(
      Function<TestComponent, ImmutableList<Provider<? extends ChannelHandler>>>
          handlerProvidersMethod,
      ImmutableSet<Class<? extends ChannelHandler>> excludedHandlers) {
    this.handlerProvidersMethod = handlerProvidersMethod;
    this.excludedHandlers = excludedHandlers;
  }

  protected ProtocolModuleTest(
      Function<TestComponent, ImmutableList<Provider<? extends ChannelHandler>>>
          handlerProvidersMethod) {
    this(handlerProvidersMethod, DEFAULT_EXCLUDED_HANDLERS);
  }

  /** Excludes handler providers that are not of interested for testing. */
  private ImmutableList<Provider<? extends ChannelHandler>> excludeHandlerProvidersForTesting(
      ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return handlerProviders.stream()
        .filter(handlerProvider -> !excludedHandlers.contains(handlerProvider.get().getClass()))
        .collect(toImmutableList());
  }

  void initializeChannel(Consumer<Channel> initializer) {
    channel =
        new EmbeddedChannel(
            new ChannelInitializer<Channel>() {
              @Override
              protected void initChannel(Channel ch) throws Exception {
                initializer.accept(ch);
              }
            });
  }

  /** Adds handlers to the channel pipeline, excluding any one in {@link #excludedHandlers}. */
  void addAllTestableHandlers(Channel ch) {
    for (Provider<? extends ChannelHandler> handlerProvider :
        excludeHandlerProvidersForTesting(handlerProvidersMethod.apply(testComponent))) {
      ch.pipeline().addLast(handlerProvider.get());
    }
  }

  static TestComponent makeTestComponent(FakeClock fakeClock) {
    return DaggerProtocolModuleTest_TestComponent.builder()
        .testModule(new TestModule(new FakeClock()))
        .build();
  }

  @BeforeEach
  void beforeEach() throws Exception {
    testComponent = makeTestComponent(new FakeClock());
    initializeChannel(this::addAllTestableHandlers);
  }

  /**
   * Component used to obtain the list of {@link ChannelHandler} providers for each {@link
   * Protocol}.
   */
  @Singleton
  @Component(
      modules = {
        TestModule.class,
        CertificateSupplierModule.class,
        WhoisProtocolModule.class,
        WebWhoisProtocolsModule.class,
        EppProtocolModule.class,
        HealthCheckProtocolModule.class,
        HttpsRelayProtocolModule.class
      })
  interface TestComponent {

    @WhoisProtocol
    ImmutableList<Provider<? extends ChannelHandler>> whoisHandlers();

    @EppProtocol
    ImmutableList<Provider<? extends ChannelHandler>> eppHandlers();

    @HealthCheckProtocol
    ImmutableList<Provider<? extends ChannelHandler>> healthCheckHandlers();

    @HttpsRelayProtocol
    ImmutableList<Provider<? extends ChannelHandler>> httpsRelayHandlers();

    @HttpWhoisProtocol
    ImmutableList<Provider<? extends ChannelHandler>> httpWhoisHandlers();
  }

  /**
   * Module that provides bindings used in tests.
   *
   * <p>Most of the binding provided in this module should be either a fake, or a {@link
   * ChannelHandler} that is excluded, and annotated with {@code @Singleton}. This module acts as a
   * replacement for {@link ProxyModule} used in production component. Providing a handler that is
   * part of the business logic of a {@link Protocol} from this module is a sign that the binding
   * should be provided in the respective {@code ProtocolModule} instead.
   */
  @Module
  static class TestModule {

    /**
     * A fake clock that is explicitly provided. Users can construct a module with a controller
     * clock.
     */
    private final FakeClock fakeClock;

    TestModule(FakeClock fakeClock) {
      this.fakeClock = fakeClock;
    }

    @Singleton
    @Provides
    static ProxyConfig provideProxyConfig() {
      return getProxyConfig(LOCAL);
    }

    @Singleton
    @Provides
    static SslProvider provideSslProvider() {
      return SslProvider.JDK;
    }

    @Singleton
    @Provides
    @Named("accessToken")
    static Supplier<String> provideFakeAccessToken() {
      return Suppliers.ofInstance("fake.test.token");
    }

    @Singleton
    @Provides
    static LoggingHandler provideLoggingHandler() {
      return new LoggingHandler();
    }

    @Singleton
    @Provides
    Clock provideFakeClock() {
      return fakeClock;
    }

    @Singleton
    @Provides
    static ExecutorService provideExecutorService() {
      return MoreExecutors.newDirectExecutorService();
    }

    @Singleton
    @Provides
    static ScheduledExecutorService provideScheduledExecutorService() {
      return Executors.newSingleThreadScheduledExecutor();
    }

    @Singleton
    @Provides
    static Environment provideEnvironment() {
      return Environment.LOCAL;
    }

    @Singleton
    @Provides
    static Mode provideMode() {
      return Mode.SELF_SIGNED;
    }

    @Singleton
    @Provides
    @Named("remoteCertCachingDuration")
    static Duration provideCertCachingDuration() {
      // Not used.
      return Duration.ofHours(1);
    }

    // This method is only here to satisfy Dagger binding, but is never used. In test environment,
    // it is the self-signed certificate and its key that end up being used.
    @Singleton
    @Provides
    @Named("pemBytes")
    static byte[] providePemBytes() {
      return new byte[0];
    }
  }
}
