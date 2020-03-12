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

package google.registry.flows;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.LoggerConfig;
import com.google.common.testing.TestLogHandler;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.ExtensionManager.UndeclaredServiceExtensionException;
import google.registry.flows.ExtensionManager.UnsupportedRepeatedExtensionException;
import google.registry.flows.exceptions.OnlyToolCanPassMetadataException;
import google.registry.flows.exceptions.UnauthorizedForSuperuserExtensionException;
import google.registry.flows.session.HelloFlow;
import google.registry.model.domain.fee06.FeeInfoCommandExtensionV06;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.superuser.DomainTransferRequestSuperuserExtension;
import google.registry.model.eppcommon.ProtocolDefinition.ServiceExtension;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.EppInput.CommandExtension;
import google.registry.testing.AppEngineRule;
import google.registry.util.TypeUtils;
import java.util.logging.LogRecord;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ExtensionManager}. */
@RunWith(JUnit4.class)
public class ExtensionManagerTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Test
  public void testDuplicateExtensionsForbidden() {
    ExtensionManager manager =
        new TestInstanceBuilder()
            .setEppRequestSource(EppRequestSource.TOOL)
            .setDeclaredUris()
            .setSuppliedExtensions(
                MetadataExtension.class, LaunchCreateExtension.class, MetadataExtension.class)
            .build();
    manager.register(MetadataExtension.class, LaunchCreateExtension.class);
    EppException thrown =
        assertThrows(UnsupportedRepeatedExtensionException.class, manager::validate);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testUndeclaredExtensionsLogged() throws Exception {
    TestLogHandler handler = new TestLogHandler();
    LoggerConfig.getConfig(ExtensionManager.class).addHandler(handler);
    ExtensionManager manager =
        new TestInstanceBuilder()
            .setEppRequestSource(EppRequestSource.TOOL)
            .setDeclaredUris()
            .setSuppliedExtensions(MetadataExtension.class)
            .build();
    manager.register(MetadataExtension.class);
    manager.validate();
    ImmutableList.Builder<String> logMessages = new ImmutableList.Builder<>();
    for (LogRecord record : handler.getStoredLogRecords()) {
      logMessages.add(record.getMessage());
    }
    assertThat(logMessages.build())
        .contains(
            "Client clientId is attempting to run HelloFlow without declaring "
                + "URIs [urn:google:params:xml:ns:metadata-1.0] on login");
  }

  @Test
  public void testBlacklistedExtensions_forbiddenWhenUndeclared() {
    ExtensionManager manager =
        new TestInstanceBuilder()
            .setEppRequestSource(EppRequestSource.TOOL)
            .setDeclaredUris()
            .setSuppliedExtensions(FeeInfoCommandExtensionV06.class)
            .build();
    manager.register(FeeInfoCommandExtensionV06.class);
    EppException thrown =
        assertThrows(UndeclaredServiceExtensionException.class, manager::validate);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testBlacklistedExtensions_allowedWhenDeclared() throws Exception {
    ExtensionManager manager =
        new TestInstanceBuilder()
            .setEppRequestSource(EppRequestSource.TOOL)
            .setDeclaredUris(ServiceExtension.FEE_0_6.getUri())
            .setSuppliedExtensions(FeeInfoCommandExtensionV06.class)
            .build();
    manager.register(FeeInfoCommandExtensionV06.class);
    manager.validate();
  }

  @Test
  public void testMetadataExtension_allowedForToolSource() throws Exception {
    ExtensionManager manager =
        new TestInstanceBuilder()
            .setEppRequestSource(EppRequestSource.TOOL)
            .setDeclaredUris()
            .setSuppliedExtensions(MetadataExtension.class)
            .build();
    manager.register(MetadataExtension.class);
    manager.validate();
  }

  @Test
  public void testMetadataExtension_forbiddenWhenNotToolSource() {
    ExtensionManager manager =
        new TestInstanceBuilder()
            .setEppRequestSource(EppRequestSource.CONSOLE)
            .setDeclaredUris()
            .setSuppliedExtensions(MetadataExtension.class)
            .build();
    manager.register(MetadataExtension.class);
    EppException thrown = assertThrows(OnlyToolCanPassMetadataException.class, manager::validate);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuperuserExtension_allowedForToolSource() throws Exception {
    ExtensionManager manager =
        new TestInstanceBuilder()
            .setEppRequestSource(EppRequestSource.TOOL)
            .setDeclaredUris()
            .setSuppliedExtensions(DomainTransferRequestSuperuserExtension.class)
            .setIsSuperuser(true)
            .build();
    manager.register(DomainTransferRequestSuperuserExtension.class);
    manager.validate();
  }

  @Test
  public void testSuperuserExtension_forbiddenWhenNotSuperuser() {
    ExtensionManager manager =
        new TestInstanceBuilder()
            .setEppRequestSource(EppRequestSource.TOOL)
            .setDeclaredUris()
            .setSuppliedExtensions(DomainTransferRequestSuperuserExtension.class)
            .setIsSuperuser(false)
            .build();
    manager.register(DomainTransferRequestSuperuserExtension.class);
    EppException thrown =
        assertThrows(UnauthorizedForSuperuserExtensionException.class, manager::validate);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuperuserExtension_forbiddenWhenNotToolSource() {
    ExtensionManager manager =
        new TestInstanceBuilder()
            .setEppRequestSource(EppRequestSource.CONSOLE)
            .setDeclaredUris()
            .setSuppliedExtensions(DomainTransferRequestSuperuserExtension.class)
            .setIsSuperuser(true)
            .build();
    manager.register(DomainTransferRequestSuperuserExtension.class);
    EppException thrown =
        assertThrows(UnauthorizedForSuperuserExtensionException.class, manager::validate);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testUnimplementedExtensionsForbidden() {
    ExtensionManager manager =
        new TestInstanceBuilder()
            .setEppRequestSource(EppRequestSource.TOOL)
            .setDeclaredUris()
            .setSuppliedExtensions(LaunchCreateExtension.class)
            .build();
    EppException thrown = assertThrows(UnimplementedExtensionException.class, manager::validate);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** A builder for a test-ready {@link ExtensionManager} instance. */
  private static class TestInstanceBuilder {

    ExtensionManager manager = new ExtensionManager();

    TestInstanceBuilder setEppRequestSource(EppRequestSource eppRequestSource) {
      manager.eppRequestSource = eppRequestSource;
      return this;
    }

    TestInstanceBuilder setDeclaredUris(String... declaredUris) {
      manager.sessionMetadata =
          new StatelessRequestSessionMetadata("clientId", ImmutableSet.copyOf(declaredUris));
      return this;
    }

    TestInstanceBuilder setIsSuperuser(boolean isSuperuser) {
      manager.isSuperuser = isSuperuser;
      return this;
    }

    @SafeVarargs
    final TestInstanceBuilder setSuppliedExtensions(
        Class<? extends CommandExtension>... suppliedExtensionClasses) {
      manager.eppInput = new FakeEppInput(suppliedExtensionClasses);
      return this;
    }

    ExtensionManager build() {
      manager.flowClass = HelloFlow.class;
      manager.clientId = manager.sessionMetadata.getClientId();
      return manager;
    }
  }

  /** A minimal fake {@link EppInput} that presents the given extensions. */
  private static class FakeEppInput extends EppInput {

    private final ImmutableList<CommandExtension> suppliedExtensions;

    @SafeVarargs
    FakeEppInput(Class<? extends CommandExtension>... suppliedExtensionClasses) {
      ImmutableList.Builder<CommandExtension> instancesBuilder = new ImmutableList.Builder<>();
      for (Class<? extends CommandExtension> clazz : suppliedExtensionClasses) {
        instancesBuilder.add(TypeUtils.<CommandExtension>instantiate(clazz));
      }
      suppliedExtensions = instancesBuilder.build();
    }

    @Override
    public CommandWrapper getCommandWrapper() {
      return new CommandWrapper() {
        @Override
        public ImmutableList<CommandExtension> getExtensions() {
          return suppliedExtensions;
        }};
    }
  }
}
