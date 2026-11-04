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
import {
  Component,
  linkedSignal,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MaterialModule } from 'src/app/material.module';
import {
  IpAllowListItem,
  RegistrarService,
} from 'src/app/registrar/registrar.service';
import {
  GlobalLoader,
  GlobalLoaderService,
} from 'src/app/shared/services/globalLoader.service';
import { SecurityService, apiToUiConverter } from './security.service';

@Component({
  selector: 'app-security-edit',
  imports: [FormsModule, MaterialModule],
  templateUrl: './securityEdit.component.html',
  styleUrls: ['./securityEdit.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecurityEditComponent implements GlobalLoader {
  dataSource = linkedSignal(() =>
    apiToUiConverter(this.registrarService.registrar())
  );
  isUpdating = signal(false);

  constructor(
    public securityService: SecurityService,
    private _snackBar: MatSnackBar,
    public registrarService: RegistrarService,
    protected globalLoader: GlobalLoaderService
  ) {}

  loadingTimeout() {
    this._snackBar.open('Timeout updating security settings');
  }

  createIpEntry() {
    this.dataSource.update((ds) => ({
      ...ds,
      ipAddressAllowList: [...(ds.ipAddressAllowList || []), { value: '' }],
    }));
  }

  save() {
    this.isUpdating.set(true);
    this.globalLoader.startGlobalLoader(this);
    this.securityService.saveChanges(this.dataSource()).subscribe({
      complete: () => {
        this.isUpdating.set(false);
        this.globalLoader.stopGlobalLoader(this);
        this.goBack();
      },
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error || err.message);
        this.isUpdating.set(false);
        this.globalLoader.stopGlobalLoader(this);
      },
    });
  }

  goBack() {
    this.securityService.isEditingSecurity.set(false);
  }

  removeIpEntry(ip: IpAllowListItem) {
    this.dataSource.update((ds) => ({
      ...ds,
      ipAddressAllowList: ds.ipAddressAllowList?.filter((item) => item !== ip),
    }));
  }
}
