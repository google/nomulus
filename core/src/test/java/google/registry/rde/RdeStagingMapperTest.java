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

package google.registry.rde;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatastoreHelper.persistNewRegistrar;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.google.appengine.tools.mapreduce.MapperContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.testing.AppEngineExtension;
import google.registry.xml.ValidationMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link RdeStagingMapper}. */
@ExtendWith(MockitoExtension.class)
class RdeStagingMapperTest {

  private static final Pattern REGISTRAR_NAME_PATTERN =
      Pattern.compile("<rdeRegistrar:name>(.*)</rdeRegistrar:name>");

  @Mock PendingDeposit pendingDeposit;

  @Mock MapperContext<PendingDeposit, DepositFragment> context;

  private ArgumentCaptor<DepositFragment> depositFragmentCaptor =
      ArgumentCaptor.forClass(DepositFragment.class);

  @RegisterExtension
  AppEngineExtension appEngineRule =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private RdeStagingMapper rdeStagingMapper;

  @BeforeEach
  void beforeEach() {
    // Two real registrars have been created by AppEngineRule, named "New Registrar" and "The
    // Registrar". Create one included registrar (external_monitoring) and two excluded ones.
    Registrar monitoringRegistrar =
        persistNewRegistrar("monitoring", "monitoring", Registrar.Type.MONITORING, null);
    Registrar testRegistrar = persistNewRegistrar("test", "test", Registrar.Type.TEST, null);
    Registrar externalMonitoringRegistrar =
        persistNewRegistrar(
            "externalmonitor", "external_monitoring", Registrar.Type.EXTERNAL_MONITORING, 9997L);

    // Set Registrar states which are required for reporting.
    tm().transact(
            () ->
                tm().saveNewOrUpdateAll(
                        ImmutableList.of(
                            externalMonitoringRegistrar.asBuilder().setState(State.ACTIVE).build(),
                            testRegistrar.asBuilder().setState(State.ACTIVE).build(),
                            monitoringRegistrar.asBuilder().setState(State.ACTIVE).build())));

    rdeStagingMapper =
        new RdeStagingMapper(ValidationMode.STRICT, ImmutableSetMultimap.of("1", pendingDeposit));
    rdeStagingMapper.setContext(context);
  }

  @Test
  void registrars_ignoreMonitoringAndTestTypes() {
    rdeStagingMapper.map(null);
    verify(context, Mockito.times(3))
        .emit(any(PendingDeposit.class), depositFragmentCaptor.capture());
    assertThat(
            depositFragmentCaptor.getAllValues().stream()
                .map(RdeStagingMapperTest::findRegistrarName))
        .containsExactly("New Registrar", "The Registrar", "external_monitoring");
  }

  private static String findRegistrarName(DepositFragment fragment) {
    Matcher matcher = REGISTRAR_NAME_PATTERN.matcher(fragment.xml());
    checkState(matcher.find(), "Missing registarName in xml.");
    return matcher.group(1);
  }
}
