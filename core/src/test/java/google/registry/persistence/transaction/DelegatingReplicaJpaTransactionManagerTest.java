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

package google.registry.persistence.transaction;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import java.util.Random;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** Tests for {@link DelegatingReplicaJpaTransactionManager}. */
public class DelegatingReplicaJpaTransactionManagerTest {

  private JpaTransactionManager replica1 = mock(JpaTransactionManager.class);
  private JpaTransactionManager replica2 = mock(JpaTransactionManager.class);
  private Random random = mock(Random.class);
  private DelegatingReplicaJpaTransactionManager transactionManager =
      new DelegatingReplicaJpaTransactionManager(ImmutableList.of(replica1, replica2), random);

  @Test
  void testGetReplica_rotates() {
    when(random.nextInt(2)).thenReturn(0).thenReturn(1);

    transactionManager.loadByKey(null);
    verify(replica1).loadByKey(null);

    transactionManager.loadByKey(null);
    verify(replica2).loadByKey(null);
  }

  @Test
  void testTransact_usesSameReplica() throws Exception {
    when(random.nextInt(2)).thenReturn(1);
    when(replica2.transact(any(), any(), anyBoolean()))
        .thenAnswer(
            invocation -> {
              Callable<Object> work = invocation.getArgument(1);
              return work.call();
            });

    transactionManager.transact(
        () -> {
          transactionManager.loadByKey(null);
          return null;
        });

    verify(replica2).transact(any(), any(), anyBoolean());
    // The loadByKey inside the transact should also use replica2.
    verify(replica2).loadByKey(null);
    // And it should NOT have called random again for the nested call.
    verify(random).nextInt(2);
  }

  @Test
  void testTransactNoRetry_usesSameReplica() throws Exception {
    when(random.nextInt(2)).thenReturn(0);
    when(replica1.transactNoRetry(any(), any(), anyBoolean()))
        .thenAnswer(
            invocation -> {
              Callable<Object> work = invocation.getArgument(1);
              return work.call();
            });

    transactionManager.transactNoRetry(
        () -> {
          transactionManager.loadByKey(null);
          return null;
        });

    verify(replica1).transactNoRetry(any(), any(), anyBoolean());
    verify(replica1).loadByKey(null);
    verify(random).nextInt(2);
  }

  @Test
  void testReTransactNoRetry_usesSameReplica() throws Exception {
    when(random.nextInt(2)).thenReturn(0);
    when(replica1.reTransact(any(Callable.class)))
        .thenAnswer(
            invocation -> {
              Callable<Object> work = invocation.getArgument(0);
              return work.call();
            });

    transactionManager.reTransact(
        () -> {
          transactionManager.loadByKey(null);
          return null;
        });

    verify(replica1).reTransact(any(Callable.class));
    verify(replica1).loadByKey(null);
    verify(random).nextInt(2);
  }

  @Test
  void testInTransaction() {
    when(random.nextInt(2)).thenReturn(0);
    when(replica1.inTransaction()).thenReturn(true);

    // Not in transaction yet
    assertThat(transactionManager.inTransaction()).isFalse();

    transactionManager.transact(
        () -> {
          assertThat(transactionManager.inTransaction()).isTrue();
          return null;
        });
  }

  @Test
  void testTeardown_tearsDownAllReplicas() {
    transactionManager.teardown();
    verify(replica1).teardown();
    verify(replica2).teardown();
  }
}
