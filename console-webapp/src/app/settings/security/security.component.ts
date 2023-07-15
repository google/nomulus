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
import { SecurityService, SecuritySettings } from './security.service';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';

@Component({
  selector: 'app-security',
  templateUrl: './security.component.html',
  styleUrls: ['./security.component.less'],
  providers: [SecurityService],
})
export default class SecurityComponent {
  loading: boolean = false;
  inEdit: boolean = false;
  dataSource: SecuritySettings = {};

  constructor(public securityService: SecurityService, private _snackBar: MatSnackBar) {
    this.loading = true;
    this.securityService.fetchSecurityDetails()
    .subscribe({
      complete: () => {
        this.dataSource = this.securityService.securitySettings;
        this.loading = false;
      },
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.statusText, undefined, {
          duration: 1500,
        });
      },
    })
  }

  enableEdit() {
    this.inEdit = true;
    this.dataSource = JSON.parse(
      JSON.stringify(this.securityService.securitySettings)
    );
  }

  disableEdit() {
    this.inEdit = true;
    this.dataSource = this.securityService.securitySettings;
  }

  createIpEntry() {
    this.dataSource.ipAddressAllowList?.push({ value: '' });
  }

  save() {
    this.loading = true;
    this.securityService.saveChanges(this.dataSource).subscribe({
      complete: () => { this.loading = false; },
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.statusText, undefined, {
          duration: 1500,
        });
      },
    });
    this.disableEdit();
  }

  cancel() {
    this.dataSource = this.securityService.securitySettings;
    this.inEdit = false;
  }

  removeIpEntry(index: number) {
    this.dataSource.ipAddressAllowList =
      this.dataSource.ipAddressAllowList?.filter((_, i) => i != index);
  }
}
