// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import { Component } from '@angular/core';
import { RegistrarService } from '../registrar/registrar.service';
import { ActivatedRoute, ParamMap } from '@angular/router';
import { RegistryLockVerifyService } from './registryLockVerify.service';
import { HttpErrorResponse } from '@angular/common/http';
import { take } from 'rxjs';
import { DomainListComponent } from '../domains/domainList.component';

@Component({
  selector: 'app-registry-lock-verify',
  templateUrl: './registryLockVerify.component.html',
  styleUrls: ['./registryLockVerify.component.scss'],
  providers: [RegistryLockVerifyService],
})
export class RegistryLockVerifyComponent {
  public static PATH = 'registry-lock-verify';

  readonly DOMAIN_LIST_COMPONENT_PATH = `/${DomainListComponent.PATH}`;

  isLoading = true;
  domainName?: string;
  action?: string;
  errorMessage?: string;

  constructor(
    protected registrarService: RegistrarService,
    protected registryLockVerifyService: RegistryLockVerifyService,
    private route: ActivatedRoute
  ) {}

  ngOnInit() {
    this.route.queryParamMap.pipe(take(1)).subscribe((params: ParamMap) => {
      this.registryLockVerifyService
        .verifyRequest(params.get('lockVerificationCode') || '')
        .subscribe({
          error: (err: HttpErrorResponse) => {
            this.isLoading = false;
            this.errorMessage = err.error;
          },
          next: (verificationResponse) => {
            this.domainName = verificationResponse.domainName;
            this.action = verificationResponse.action;
            this.registrarService.registrarId.set(
              verificationResponse.registrarId
            );
            this.isLoading = false;
          },
        });
    });
  }
}
