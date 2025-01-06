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

import { Injectable } from '@angular/core';
import { tap } from 'rxjs';
import { RegistrarService } from '../registrar/registrar.service';
import { BackendService } from '../shared/services/backend.service';

export interface DomainLocksResult {
  domainName: string;
}

@Injectable({
  providedIn: 'root',
})
export class RegistryLockService {
  public domainsLocks: DomainLocksResult[] = [];

  constructor(
    private backendService: BackendService,
    private registrarService: RegistrarService
  ) {}

  retrieveLocks() {
    return this.backendService
      .getLocks(this.registrarService.registrarId())
      .pipe(
        tap((domainLocksResult) => {
          this.domainsLocks = domainLocksResult;
        })
      );
  }

  registryLockDomain(
    domainName: string,
    password: string,
    relockDurationMillis: number | undefined,
    isLock: boolean
  ) {
    return this.backendService.registryLockDomain(
      domainName,
      password,
      relockDurationMillis,
      this.registrarService.registrarId(),
      isLock
    );
  }
}
