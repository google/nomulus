// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rdap;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.console.RegistrarRole.ACCOUNT_MANAGER;
import static google.registry.rdap.RdapAuthorization.ADMINISTRATOR_AUTHORIZATION;
import static google.registry.rdap.RdapAuthorization.PUBLIC_AUTHORIZATION;
import static google.registry.request.auth.AuthResult.NOT_AUTHENTICATED;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.OWNER;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import dagger.BindsInstance;
import dagger.Component;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.request.RequestScope;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link RdapModule}. */
@ExtendWith(MockitoExtension.class)
class RdapModuleTest {

  @Mock private Lazy<AuthenticatedRegistrarAccessor> lazyRegistrarAccessor;
  @Mock private HttpServletRequest httpServletRequest;

  @RequestScope
  @Component(modules = {RdapModule.class, TestAccessorModule.class})
  interface TestRdapComponent {
    RdapAuthorization rdapAuthorization();

    @Component.Builder
    interface Builder {
      @BindsInstance
      Builder authResult(AuthResult authResult);

      @BindsInstance
      Builder httpServletRequest(HttpServletRequest httpServletRequest);

      @BindsInstance
      Builder accessorSupplier(Supplier<AuthenticatedRegistrarAccessor> accessorSupplier);

      TestRdapComponent build();
    }
  }

  @Module
  static final class TestAccessorModule {
    @Provides
    static AuthenticatedRegistrarAccessor provideRegistrarAccessor(
        Supplier<AuthenticatedRegistrarAccessor> accessorSupplier) {
      return accessorSupplier.get();
    }
  }

  private static AuthResult createUserAuthResult(boolean isAdmin) {
    return AuthResult.createUser(
        new User.Builder()
            .setEmailAddress("johndoe@theregistrar.com")
            .setUserRoles(
                new UserRoles.Builder()
                    .setIsAdmin(isAdmin)
                    .setRegistrarRoles(ImmutableMap.of("TheRegistrar", ACCOUNT_MANAGER))
                    .build())
            .build());
  }

  @Test
  void testProvideRdapAuthorization_notAuthenticated_returnsPublicWithoutTouchingAccessor() {
    RdapAuthorization authorization =
        RdapModule.provideRdapAuthorization(NOT_AUTHENTICATED, lazyRegistrarAccessor);

    assertThat(authorization).isEqualTo(PUBLIC_AUTHORIZATION);
    verifyNoInteractions(lazyRegistrarAccessor);
  }

  @Test
  void testProvideRdapAuthorization_adminUser_returnsAdminWithoutTouchingAccessor() {
    RdapAuthorization authorization =
        RdapModule.provideRdapAuthorization(createUserAuthResult(true), lazyRegistrarAccessor);

    assertThat(authorization).isEqualTo(ADMINISTRATOR_AUTHORIZATION);
    verifyNoInteractions(lazyRegistrarAccessor);
  }

  @Test
  void testProvideRdapAuthorization_registrarUser_invokesLazyAccessorOnce() {
    AuthenticatedRegistrarAccessor accessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of("TheRegistrar", OWNER));
    when(lazyRegistrarAccessor.get()).thenReturn(accessor);

    RdapAuthorization authorization =
        RdapModule.provideRdapAuthorization(createUserAuthResult(false), lazyRegistrarAccessor);

    assertThat(authorization)
        .isEqualTo(
            RdapAuthorization.create(
                RdapAuthorization.Role.REGISTRAR, ImmutableSet.of("TheRegistrar")));
    verify(lazyRegistrarAccessor).get();
  }

  @Test
  void testProvideRdapAuthorization_registrarUserWithNoRegistrars_returnsPublic() {
    AuthenticatedRegistrarAccessor emptyAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of());
    when(lazyRegistrarAccessor.get()).thenReturn(emptyAccessor);

    RdapAuthorization authorization =
        RdapModule.provideRdapAuthorization(createUserAuthResult(false), lazyRegistrarAccessor);

    assertThat(authorization).isEqualTo(PUBLIC_AUTHORIZATION);
    verify(lazyRegistrarAccessor).get();
  }

  @Test
  void testProvideRdapAuthorization_hasRequestScopeAnnotationAndCachesPerRequest()
      throws NoSuchMethodException {
    Method method =
        RdapModule.class.getDeclaredMethod(
            "provideRdapAuthorization", AuthResult.class, Lazy.class);
    assertThat(method.isAnnotationPresent(RequestScope.class)).isTrue();

    AtomicInteger accessorInstantiations = new AtomicInteger();
    Supplier<AuthenticatedRegistrarAccessor> countingSupplier =
        () -> {
          accessorInstantiations.incrementAndGet();
          return AuthenticatedRegistrarAccessor.createForTesting(
              ImmutableSetMultimap.of("TheRegistrar", OWNER));
        };

    TestRdapComponent unauthComponent =
        DaggerRdapModuleTest_TestRdapComponent.builder()
            .authResult(NOT_AUTHENTICATED)
            .httpServletRequest(httpServletRequest)
            .accessorSupplier(countingSupplier)
            .build();
    assertThat(unauthComponent.rdapAuthorization()).isEqualTo(PUBLIC_AUTHORIZATION);
    assertThat(unauthComponent.rdapAuthorization()).isEqualTo(PUBLIC_AUTHORIZATION);
    assertThat(accessorInstantiations.get()).isEqualTo(0);

    TestRdapComponent registrarComponent =
        DaggerRdapModuleTest_TestRdapComponent.builder()
            .authResult(createUserAuthResult(false))
            .httpServletRequest(httpServletRequest)
            .accessorSupplier(countingSupplier)
            .build();
    RdapAuthorization first = registrarComponent.rdapAuthorization();
    RdapAuthorization second = registrarComponent.rdapAuthorization();
    assertThat(first).isSameInstanceAs(second);
    assertThat(accessorInstantiations.get()).isEqualTo(1);
  }
}
