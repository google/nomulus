// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.util.Retrier;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link WipeOutCloudSqlAction}. */
@ExtendWith(MockitoExtension.class)
public class WipeOutCloudSqlActionTest {

  @Mock private Statement stmt;
  @Mock private Connection conn;
  @Mock private DatabaseMetaData metaData;
  @Mock private ResultSet resultSet;

  private FakeResponse response = new FakeResponse();
  private Retrier retrier = new Retrier(new FakeSleeper(new FakeClock()), 2);

  @BeforeEach
  void beforeEach() throws Exception {
    lenient().when(conn.createStatement()).thenReturn(stmt);
    lenient().when(conn.getMetaData()).thenReturn(metaData);
    lenient()
        .when(
            metaData.getTables(
                nullable(String.class),
                nullable(String.class),
                nullable(String.class),
                nullable(String[].class)))
        .thenReturn(resultSet);
    lenient().when(stmt.executeQuery(anyString())).thenReturn(resultSet);
    lenient().when(resultSet.next()).thenReturn(false);
  }

  @Test
  void run_projectAllowed() throws Exception {
    WipeOutCloudSqlAction action =
        new WipeOutCloudSqlAction("domain-registry-qa", () -> conn, response, retrier);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(stmt, times(1)).executeQuery(anyString());
    verify(stmt, times(1)).close();
    verifyNoMoreInteractions(stmt);
  }

  @Test
  void run_projectNotAllowed() {
    WipeOutCloudSqlAction action =
        new WipeOutCloudSqlAction("domain-registry", () -> conn, response, retrier);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
    verifyNoInteractions(stmt);
  }

  @Test
  void run_nonRetrieableFailure() throws Exception {
    doThrow(new SQLException()).when(conn).getMetaData();
    WipeOutCloudSqlAction action =
        new WipeOutCloudSqlAction("domain-registry-qa", () -> conn, response, retrier);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    verifyNoInteractions(stmt);
  }

  @Test
  void run_retrieableFailure() throws Exception {
    when(conn.getMetaData()).thenThrow(new RuntimeException()).thenReturn(metaData);
    WipeOutCloudSqlAction action =
        new WipeOutCloudSqlAction("domain-registry-qa", () -> conn, response, retrier);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(stmt, times(1)).executeQuery(anyString());
    verify(stmt, times(1)).close();
    verifyNoMoreInteractions(stmt);
  }
}
