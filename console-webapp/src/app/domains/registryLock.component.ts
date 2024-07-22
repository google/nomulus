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

import { Component, computed } from '@angular/core';
import { FormControl, FormGroup } from '@angular/forms';
import { RegistrarService } from '../registrar/registrar.service';
import { Domain, DomainListService } from './domainList.service';

@Component({
  selector: 'app-registry-lock',
  templateUrl: './registryLock.component.html',
  styleUrls: ['./registryLock.component.scss'],
})
export class RegistryLockComponent {
  domain = computed<Domain>(() =>
    // @ts-ignore - domain always exists here because it's matching the one from the table
    this.domainListService.domainsList.find(
      (d: Domain) => d.domainName === this.domainListService.selectedDomain
    )
  );

  relockOptions = [
    { name: '1 hour', duration: 3600000 },
    { name: '6 hours', duration: 21600000 },
    { name: '24 hours', duration: 86400000 },
    { name: 'Never', duration: undefined },
  ];

  lockDomain = new FormGroup({
    password: new FormControl(''),
  });

  unlockDomain = new FormGroup({
    password: new FormControl(''),
    relockTime: new FormControl(undefined),
  });

  constructor(
    protected registrarService: RegistrarService,
    protected domainListService: DomainListService
  ) {}

  goBack() {
    this.domainListService.selectedDomain = undefined;
    this.domainListService.activeActionComponent = null;
  }

  save(isLock: boolean) {
    if (!isLock) {
      this.domainListService.registryLockDomain(
        this.domain().domainName,
        this.unlockDomain.value.password || '',
        this.unlockDomain.value.relockTime || undefined,
        isLock
      );
    } else {
      this.domainListService.registryLockDomain(
        this.domain().domainName,
        this.lockDomain.value.password || '',
        undefined,
        isLock
      );
    }
  }
}
