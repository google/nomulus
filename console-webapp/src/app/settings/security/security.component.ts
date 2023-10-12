// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
import {
  SecurityService,
  SecuritySettings,
  apiToUiConverter,
} from './security.service';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RegistrarService } from 'src/app/registrar/registrar.service';

@Component({
  selector: 'app-security',
  templateUrl: './security.component.html',
  styleUrls: ['./security.component.scss'],
  providers: [SecurityService],
})
export default class SecurityComponent {
  public static PATH = 'security';

  loading: boolean = false;
  inEdit: boolean = false;
  dataSource: SecuritySettings = {};

  constructor(
    public securityService: SecurityService,
    private _snackBar: MatSnackBar,
    public registrarService: RegistrarService
  ) {
    this.dataSource = apiToUiConverter(this.registrarService.registrar);
  }

  enableEdit() {
    this.inEdit = true;
  }

  cancel() {
    this.inEdit = false;
    this.resetDataSource();
  }

  createIpEntry() {
    this.dataSource.ipAddressAllowList?.push({ value: '' });
  }

  save() {
    this.loading = true;
    this.securityService.saveChanges(this.dataSource).subscribe({
      complete: () => {
        this.loading = false;
        this.resetDataSource();
      },
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error);
      },
    });
    this.cancel();
  }

  removeIpEntry(index: number) {
    this.dataSource.ipAddressAllowList =
      this.dataSource.ipAddressAllowList?.filter((_, i) => i != index);
  }

  resetDataSource() {
    this.dataSource = apiToUiConverter(this.registrarService.registrar);
  }
}
