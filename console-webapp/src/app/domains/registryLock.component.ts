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

import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed } from '@angular/core';
import { FormControl, FormGroup } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RegistrarService } from '../registrar/registrar.service';
import { UserDataService } from '../shared/services/userData.service';
import { DomainListService } from './domainList.service';
import { RegistryLockService } from './registryLock.service';

@Component({
  selector: 'app-registry-lock',
  templateUrl: './registryLock.component.html',
  styleUrls: ['./registryLock.component.scss'],
  standalone: false,
})
export class RegistryLockComponent {
  readonly isLocked = computed(() =>
    this.registryLockService.domainsLocks.some(
      (dl) => dl.domainName === this.domainListService.selectedDomain
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
    protected domainListService: DomainListService,
    protected registryLockService: RegistryLockService,
    protected userDataService: UserDataService,
    private _snackBar: MatSnackBar
  ) {}

  goBack() {
    this.domainListService.selectedDomain = undefined;
    this.domainListService.activeActionComponent = null;
  }

  save(isLock: boolean) {
    let request;
    if (!isLock) {
      request = this.registryLockService.registryLockDomain(
        this.domainListService.selectedDomain || '',
        this.unlockDomain.value.password || '',
        this.unlockDomain.value.relockTime || undefined,
        isLock
      );
    } else {
      request = this.registryLockService.registryLockDomain(
        this.domainListService.selectedDomain || '',
        this.lockDomain.value.password || '',
        undefined,
        isLock
      );
    }

    request.subscribe({
      complete: () => {
        this.goBack();
      },
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error);
      },
    });
  }
}
