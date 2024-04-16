// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.cloudresourcemanager.CloudResourceManager;
import com.google.api.services.cloudresourcemanager.CloudResourceManager.Projects;
import com.google.api.services.cloudresourcemanager.CloudResourceManager.Projects.GetIamPolicy;
import com.google.api.services.cloudresourcemanager.CloudResourceManager.Projects.SetIamPolicy;
import com.google.api.services.cloudresourcemanager.model.Binding;
import com.google.api.services.cloudresourcemanager.model.GetIamPolicyRequest;
import com.google.api.services.cloudresourcemanager.model.Policy;
import com.google.api.services.cloudresourcemanager.model.SetIamPolicyRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link IamClient}. */
public class IamClientTest {
  private final CloudResourceManager resourceManager = mock(CloudResourceManager.class);
  private final String projectId = "my-project";
  private final String account = "test@example.test";
  private final String role = "roles/fakeRole";
  private final IamClient client = new IamClient(resourceManager, projectId);
  private final Projects projects = mock(Projects.class);
  private final List<Binding> bindings = new ArrayList<>();

  void setupRequest() throws Exception {
    when(resourceManager.projects()).thenReturn(projects);
    // Set up getPolicy()
    GetIamPolicy getIamPolicy = mock(GetIamPolicy.class);
    when(projects.getIamPolicy(eq(projectId), any(GetIamPolicyRequest.class)))
        .thenReturn(getIamPolicy);
    Policy policy = mock(Policy.class);
    when(getIamPolicy.execute()).thenReturn(policy);
    Binding nonMatchedBinding = mock(Binding.class);
    when(nonMatchedBinding.getRole()).thenReturn("roles/nonRelatedRole");
    bindings.add(nonMatchedBinding);
    when(policy.getBindings()).thenReturn(bindings);

    // Set up setPolicy()
    SetIamPolicy setIamPolicy = mock(SetIamPolicy.class);
    when(projects.setIamPolicy(eq(projectId), any(SetIamPolicyRequest.class)))
        .thenReturn(setIamPolicy);
  }

  void verifyResponse() throws Exception {
    ArgumentCaptor<SetIamPolicyRequest> setPolicyRequest =
        ArgumentCaptor.forClass(SetIamPolicyRequest.class);
    verify(projects).setIamPolicy(eq(projectId), setPolicyRequest.capture());
    assertThat(setPolicyRequest.getValue().getPolicy().getBindings()).isEqualTo(bindings);
  }

  @Test
  void testSuccess_findUser() {
    Binding binding = mock(Binding.class);
    List<String> members = new ArrayList<>();
    when(binding.getMembers()).thenReturn(members);
    members.add("user:" + account);
    members.add("serviceAccount:service@example.test");
    assertThat(IamClient.findMember(binding, "user:" + account)).hasValue("user:" + account);
    assertThat(IamClient.findMember(binding, "serviceAccount:" + account)).isEmpty();
    // Make sure that the match is case-insensitive.
    assertThat(IamClient.findMember(binding, "serviceAccount:SERVICE@example.test"))
        .hasValue("serviceAccount:service@example.test");
  }

  @Test
  void testSuccess_addBinding_noMatchedBindingExists() throws Exception {
    setupRequest();
    assertThat(bindings.size()).isEqualTo(1);
    client.addBinding(account, role);
    assertThat(bindings.size()).isEqualTo(2);
    Binding binding = bindings.get(1);
    assertThat(binding.getRole()).isEqualTo(role);
    assertThat(binding.getMembers()).containsExactly("user:" + account);
    verifyResponse();
  }

  @Test
  void testSuccess_addBinding_matchBindingExists() throws Exception {
    setupRequest();
    Binding matchedBinding = mock(Binding.class);
    when(matchedBinding.getRole()).thenReturn(role);
    List<String> existingMembers = new ArrayList<>();
    existingMembers.add("serviceAccount:service@example.test");
    when(matchedBinding.getMembers()).thenReturn(existingMembers);
    bindings.add(matchedBinding);
    assertThat(bindings.size()).isEqualTo(2);
    client.addBinding(account, role);
    assertThat(bindings.size()).isEqualTo(2);
    assertThat(existingMembers)
        .containsExactly("serviceAccount:service@example.test", "user:" + account);
    verifyResponse();
  }

  @Test
  void testSuccess_addBinding_matchedBindingExists_memberExists() throws Exception {
    setupRequest();
    Binding matchedBinding = mock(Binding.class);
    when(matchedBinding.getRole()).thenReturn(role);
    List<String> existingMembers = new ArrayList<>();
    existingMembers.add("serviceAccount:service@example.test");
    existingMembers.add("user:" + account);
    when(matchedBinding.getMembers()).thenReturn(existingMembers);
    bindings.add(matchedBinding);
    assertThat(bindings.size()).isEqualTo(2);
    client.addBinding(account, role);
    assertThat(bindings.size()).isEqualTo(2);
    assertThat(existingMembers)
        .containsExactly("serviceAccount:service@example.test", "user:" + account);
    verifyResponse();
  }

  @Test
  void testSuccess_removeBinding_noMatchedBindingExists() throws Exception {
    setupRequest();
    assertThat(bindings.size()).isEqualTo(1);
    client.removeBinding(account, role);
    assertThat(bindings.size()).isEqualTo(1);
    Binding binding = bindings.get(0);
    assertThat(binding.getRole()).isEqualTo("roles/nonRelatedRole");
    verifyResponse();
  }

  @Test
  void testSuccess_removeBinding_matchedBindingExists() throws Exception {
    setupRequest();
    Binding matchedBinding = mock(Binding.class);
    when(matchedBinding.getRole()).thenReturn(role);
    List<String> existingMembers = new ArrayList<>();
    existingMembers.add("serviceAccount:service@example.test");
    when(matchedBinding.getMembers()).thenReturn(existingMembers);
    bindings.add(matchedBinding);
    assertThat(bindings.size()).isEqualTo(2);
    client.removeBinding(account, role);
    assertThat(bindings.size()).isEqualTo(2);
    assertThat(existingMembers).containsExactly("serviceAccount:service@example.test");
    verifyResponse();
  }

  @Test
  void testSuccess_removeBinding_matchedBindingExists_memberExisits() throws Exception {
    setupRequest();
    Binding matchedBinding = mock(Binding.class);
    when(matchedBinding.getRole()).thenReturn(role);
    List<String> existingMembers = new ArrayList<>();
    existingMembers.add("serviceAccount:service@example.test");
    existingMembers.add("user:" + account);
    when(matchedBinding.getMembers()).thenReturn(existingMembers);
    bindings.add(matchedBinding);
    assertThat(bindings.size()).isEqualTo(2);
    client.removeBinding(account, role);
    assertThat(bindings.size()).isEqualTo(2);
    assertThat(existingMembers).containsExactly("serviceAccount:service@example.test");
    verifyResponse();
  }
}
