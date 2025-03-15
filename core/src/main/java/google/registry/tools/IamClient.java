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

import com.google.api.services.cloudresourcemanager.CloudResourceManager;
import com.google.api.services.cloudresourcemanager.model.Binding;
import com.google.api.services.cloudresourcemanager.model.GetIamPolicyRequest;
import com.google.api.services.cloudresourcemanager.model.Policy;
import com.google.api.services.cloudresourcemanager.model.SetIamPolicyRequest;
import com.google.common.base.Ascii;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.GoogleCredentialsBundle;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;

@Singleton
public class IamClient {
  private static final String MEMBER_FORMAT = "user:%s";

  private final CloudResourceManager resourceManager;
  private final String projectId;

  @Inject
  public IamClient(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("projectId") String projectId) {
    this(
        new CloudResourceManager.Builder(
                credentialsBundle.getHttpTransport(),
                credentialsBundle.getJsonFactory(),
                credentialsBundle.getHttpRequestInitializer())
            .setApplicationName(projectId)
            .build(),
        projectId);
  }

  protected IamClient(CloudResourceManager resourceManager, String projectId) {
    this.resourceManager = resourceManager;
    this.projectId = projectId;
  }

  /**
   * Bind the given role to the account.
   *
   * <p>No-op if the role is already bound to the account.
   */
  public void addBinding(String account, String role) {
    String member = String.format(MEMBER_FORMAT, account);
    Policy policy = getPolicy();
    Binding binding =
        policy.getBindings().stream()
            .filter(b -> b.getRole().equals(role))
            .findFirst()
            .orElseGet(
                () -> {
                  Binding newBinding = new Binding().setRole(role).setMembers(new ArrayList<>());
                  policy.getBindings().add(newBinding);
                  return newBinding;
                });
    if (findMember(binding, member).isEmpty()) {
      binding.getMembers().add(member);
      setPolicy(policy);
    }
  }

  /**
   * Remove the given role to the account.
   *
   * <p>No-op if the role is not bound to the account.
   */
  public void removeBinding(String account, String role) {
    String member = String.format(MEMBER_FORMAT, account);
    Policy policy = getPolicy();
    policy.getBindings().stream()
        .filter(b -> b.getRole().equals(role))
        .findFirst()
        .ifPresent(
            b ->
                findMember(b, member)
                    .ifPresent(
                        m -> {
                          b.getMembers().remove(m);
                          if (b.getMembers().isEmpty()) {
                            policy.getBindings().remove(b);
                          }
                          setPolicy(policy);
                        }));
  }

  protected static Optional<String> findMember(Binding binding, String member) {
    return binding.getMembers().stream()
        // Standardize email addresses for comparison.
        .filter(m -> Ascii.toLowerCase(m).equals(Ascii.toLowerCase(member)))
        .findFirst();
  }

  private Policy getPolicy() {
    try {
      GetIamPolicyRequest request = new GetIamPolicyRequest();
      return resourceManager.projects().getIamPolicy(projectId, request).execute();
    } catch (IOException e) {
      throw new RuntimeException(String.format("Cannot get policy for project %s", projectId), e);
    }
  }

  private void setPolicy(Policy policy) {
    try {
      SetIamPolicyRequest request = new SetIamPolicyRequest();
      request.setPolicy(policy);
      resourceManager.projects().setIamPolicy(projectId, request).execute();
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("Cannot set policy %s for project %s", policy, projectId), e);
    }
  }
}
