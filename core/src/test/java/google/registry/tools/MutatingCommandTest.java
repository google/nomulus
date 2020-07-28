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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.deleteResource;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistNewRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.Assert.assertThrows;

import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.testing.AppEngineExtension;
import java.util.Arrays;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link MutatingCommand}. */
public class MutatingCommandTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private Registrar registrar1;
  private Registrar registrar2;
  private Registrar newRegistrar1;
  private Registrar newRegistrar2;
  private HostResource host1;
  private HostResource host2;
  private HostResource newHost1;
  private HostResource newHost2;

  @BeforeEach
  void beforeEach() {
    registrar1 = persistNewRegistrar("Registrar1", "Registrar1", Registrar.Type.REAL, 1L);
    registrar2 = persistNewRegistrar("Registrar2", "Registrar2", Registrar.Type.REAL, 2L);
    newRegistrar1 = registrar1.asBuilder().setBillingIdentifier(42L).build();
    newRegistrar2 = registrar2.asBuilder().setBlockPremiumNames(true).build();

    createTld("tld");
    host1 = persistActiveHost("host1.example.tld");
    host2 = persistActiveHost("host2.example.tld");
    newHost1 = host1.asBuilder()
        .setLastEppUpdateTime(DateTime.parse("2014-09-09T09:09:09.000Z"))
        .build();
    newHost2 = host2.asBuilder().setPersistedCurrentSponsorClientId("Registrar2").build();
  }

  @Test
  void testSuccess_noChanges() throws Exception {
    MutatingCommand command = new MutatingCommand() {
      @Override
      protected void init() {}
    };
    command.init();
    assertThat(command.prompt()).isEqualTo("No entity changes to apply.");
    assertThat(command.execute()).isEqualTo("Updated 0 entities.\n");
  }

  @Test
  void testSuccess_update() throws Exception {
    MutatingCommand command = new MutatingCommand() {
      @Override
      public void init() {
        stageEntityChange(host1, newHost1);
        stageEntityChange(host2, newHost2);
        stageEntityChange(registrar1, newRegistrar1);
        stageEntityChange(registrar2, newRegistrar2);
      }
    };
    command.init();
    String changes = command.prompt();
    assertThat(changes).isEqualTo(
        "Update HostResource@2-ROID\n"
            + "lastEppUpdateTime: null -> 2014-09-09T09:09:09.000Z\n"
            + "\n"
            + "Update HostResource@3-ROID\n"
            + "currentSponsorClientId: TheRegistrar -> Registrar2\n"
            + "\n"
            + "Update Registrar@Registrar1\n"
            + "billingIdentifier: null -> 42\n"
            + "\n"
            + "Update Registrar@Registrar2\n"
            + "blockPremiumNames: false -> true\n");
    String results = command.execute();
    assertThat(results).isEqualTo("Updated 4 entities.\n");
    assertThat(ofy().load().entity(host1).now()).isEqualTo(newHost1);
    assertThat(ofy().load().entity(host2).now()).isEqualTo(newHost2);
    assertThat(ofy().load().entity(registrar1).now()).isEqualTo(newRegistrar1);
    assertThat(ofy().load().entity(registrar2).now()).isEqualTo(newRegistrar2);
  }

  @Test
  void testSuccess_create() throws Exception {
    ofy().deleteWithoutBackup().entities(Arrays.asList(host1, host2, registrar1, registrar2)).now();
    MutatingCommand command = new MutatingCommand() {
      @Override
      protected void init() {
        stageEntityChange(null, newHost1);
        stageEntityChange(null, newHost2);
        stageEntityChange(null, newRegistrar1);
        stageEntityChange(null, newRegistrar2);
      }
    };
    command.init();
    String changes = command.prompt();
    assertThat(changes).isEqualTo(
        "Create HostResource@2-ROID\n"
            + newHost1 + "\n"
            + "\n"
            + "Create HostResource@3-ROID\n"
            + newHost2 + "\n"
            + "\n"
            + "Create Registrar@Registrar1\n"
            + newRegistrar1 + "\n"
            + "\n"
            + "Create Registrar@Registrar2\n"
            + newRegistrar2 + "\n");
    String results = command.execute();
    assertThat(results).isEqualTo("Updated 4 entities.\n");
    assertThat(ofy().load().entity(newHost1).now()).isEqualTo(newHost1);
    assertThat(ofy().load().entity(newHost2).now()).isEqualTo(newHost2);
    assertThat(ofy().load().entity(newRegistrar1).now()).isEqualTo(newRegistrar1);
    assertThat(ofy().load().entity(newRegistrar2).now()).isEqualTo(newRegistrar2);
  }

  @Test
  void testSuccess_delete() throws Exception {
    MutatingCommand command = new MutatingCommand() {
      @Override
      protected void init() {
        stageEntityChange(host1, null);
        stageEntityChange(host2, null);
        stageEntityChange(registrar1, null);
        stageEntityChange(registrar2, null);
      }
    };
    command.init();
    String changes = command.prompt();
    assertThat(changes).isEqualTo(
        "Delete HostResource@2-ROID\n"
            + host1 + "\n"
            + "\n"
            + "Delete HostResource@3-ROID\n"
            + host2 + "\n"
            + "\n"
            + "Delete Registrar@Registrar1\n"
            + registrar1 + "\n"
            + "\n"
            + "Delete Registrar@Registrar2\n"
            + registrar2 + "\n");
    String results = command.execute();
    assertThat(results).isEqualTo("Updated 4 entities.\n");
    assertThat(ofy().load().entity(host1).now()).isNull();
    assertThat(ofy().load().entity(host2).now()).isNull();
    assertThat(ofy().load().entity(registrar1).now()).isNull();
    assertThat(ofy().load().entity(registrar2).now()).isNull();
  }

  @Test
  void testSuccess_noopUpdate() throws Exception {
    MutatingCommand command = new MutatingCommand() {
      @Override
      protected void init() {
        stageEntityChange(host1, host1);
        stageEntityChange(registrar1, registrar1);
      }
    };
    command.init();
    String changes = command.prompt();
    System.out.println(changes);
    assertThat(changes).isEqualTo(
        "Update HostResource@2-ROID\n"
            + "[no changes]\n"
            + "\n"
            + "Update Registrar@Registrar1\n"
            + "[no changes]\n");
    String results = command.execute();
    assertThat(results).isEqualTo("Updated 2 entities.\n");
    assertThat(ofy().load().entity(host1).now()).isEqualTo(host1);
    assertThat(ofy().load().entity(registrar1).now()).isEqualTo(registrar1);
  }

  @Test
  void testSuccess_batching() throws Exception {
    MutatingCommand command = new MutatingCommand() {
      @Override
      protected void init() {
        stageEntityChange(host1, null);
        stageEntityChange(host2, newHost2);
        flushTransaction();
        flushTransaction(); // Flushing should be idempotent.
        stageEntityChange(registrar1, null);
        stageEntityChange(registrar2, newRegistrar2);
        // Even though there is no trailing flushTransaction(), these last two should be executed.
      }
    };
    command.init();
    String changes = command.prompt();
    assertThat(changes).isEqualTo(
        "Delete HostResource@2-ROID\n"
            + host1 + "\n"
            + "\n"
            + "Update HostResource@3-ROID\n"
            + "currentSponsorClientId: TheRegistrar -> Registrar2\n"
            + "\n"
            + "Delete Registrar@Registrar1\n"
            + registrar1 + "\n"
            + "\n"
            + "Update Registrar@Registrar2\n"
            + "blockPremiumNames: false -> true\n");
    String results = command.execute();
    assertThat(results).isEqualTo("Updated 4 entities.\n");
    assertThat(ofy().load().entity(host1).now()).isNull();
    assertThat(ofy().load().entity(host2).now()).isEqualTo(newHost2);
    assertThat(ofy().load().entity(registrar1).now()).isNull();
    assertThat(ofy().load().entity(registrar2).now()).isEqualTo(newRegistrar2);
  }

  @Test
  void testSuccess_batching_partialExecutionWorks() throws Exception {
    // The expected behavior here is that the first transaction will work and be committed, and
    // the second transaction will throw an IllegalStateException and not commit.
    MutatingCommand command = new MutatingCommand() {
      @Override
      protected void init() {
        stageEntityChange(host1, null);
        stageEntityChange(host2, newHost2);
        flushTransaction();
        stageEntityChange(registrar1, null);
        stageEntityChange(registrar2, newRegistrar2); // This will fail.
        flushTransaction();
      }
    };
    command.init();
    // Save an update to registrar2 that will cause the second transaction to fail because the
    // resource has been updated since the command inited the resources to process.
    registrar2 = persistResource(registrar2.asBuilder().setContactsRequireSyncing(false).build());
    String changes = command.prompt();
    assertThat(changes).isEqualTo(
        "Delete HostResource@2-ROID\n"
            + host1 + "\n"
            + "\n"
            + "Update HostResource@3-ROID\n"
            + "currentSponsorClientId: TheRegistrar -> Registrar2\n"
            + "\n"
            + "Delete Registrar@Registrar1\n"
            + registrar1 + "\n"
            + "\n"
            + "Update Registrar@Registrar2\n"
            + "blockPremiumNames: false -> true\n");

    IllegalStateException thrown = assertThrows(IllegalStateException.class, command::execute);
    assertThat(thrown).hasMessageThat().contains("Entity changed since init() was called.");
    assertThat(ofy().load().entity(host1).now()).isNull();
    assertThat(ofy().load().entity(host2).now()).isEqualTo(newHost2);
    // These two shouldn't've changed.
    assertThat(ofy().load().entity(registrar1).now()).isEqualTo(registrar1);
    assertThat(ofy().load().entity(registrar2).now()).isEqualTo(registrar2);
  }

  @Test
  void testFailure_nullEntityChange() {
    MutatingCommand command = new MutatingCommand() {
      @Override
      protected void init() {
        stageEntityChange(null, null);
      }
    };
    assertThrows(IllegalArgumentException.class, command::init);
  }

  @Test
  void testFailure_updateSameEntityTwice() {
    MutatingCommand command =
        new MutatingCommand() {
          @Override
          protected void init() {
            stageEntityChange(host1, newHost1);
            stageEntityChange(
                host1, host1.asBuilder().setLastEppUpdateTime(DateTime.now(UTC)).build());
          }
        };
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cannot apply multiple changes for the same entity");
  }

  @Test
  void testFailure_updateDifferentLongId() {
    MutatingCommand command = new MutatingCommand() {
      @Override
      protected void init() {
        stageEntityChange(host1, host2);
      }
    };
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Both entity versions in an update must have the same Key.");
  }

  @Test
  void testFailure_updateDifferentStringId() {
    MutatingCommand command =
        new MutatingCommand() {
          @Override
          public void init() {
            stageEntityChange(registrar1, registrar2);
          }
        };
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Both entity versions in an update must have the same Key.");
  }

  @Test
  void testFailure_updateDifferentKind() {
    MutatingCommand command =
        new MutatingCommand() {
          @Override
          public void init() {
            stageEntityChange(host1, registrar1);
          }
        };
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Both entity versions in an update must have the same Key.");
  }

  @Test
  void testFailure_updateModifiedEntity() throws Exception {
    MutatingCommand command =
        new MutatingCommand() {
          @Override
          public void init() {
            stageEntityChange(host1, newHost1);
          }
        };
    command.init();
    persistResource(newHost1);
    IllegalStateException thrown = assertThrows(IllegalStateException.class, command::execute);
    assertThat(thrown).hasMessageThat().contains("Entity changed since init() was called.");
  }

  @Test
  void testFailure_createExistingEntity() throws Exception {
    MutatingCommand command =
        new MutatingCommand() {
          @Override
          protected void init() {
            stageEntityChange(null, newHost1);
          }
        };
    command.init();
    persistResource(newHost1);
    IllegalStateException thrown = assertThrows(IllegalStateException.class, command::execute);
    assertThat(thrown).hasMessageThat().contains("Entity changed since init() was called.");
  }

  @Test
  void testFailure_deleteChangedEntity() throws Exception {
    MutatingCommand command =
        new MutatingCommand() {
          @Override
          protected void init() {
            stageEntityChange(host1, null);
          }
        };
    command.init();
    persistResource(newHost1);
    IllegalStateException thrown = assertThrows(IllegalStateException.class, command::execute);
    assertThat(thrown).hasMessageThat().contains("Entity changed since init() was called.");
  }

  @Test
  void testFailure_deleteRemovedEntity() throws Exception {
    MutatingCommand command =
        new MutatingCommand() {
          @Override
          protected void init() {
            stageEntityChange(host1, null);
          }
        };
    command.init();
    deleteResource(host1);
    IllegalStateException thrown = assertThrows(IllegalStateException.class, command::execute);
    assertThat(thrown).hasMessageThat().contains("Entity changed since init() was called.");
  }
}
