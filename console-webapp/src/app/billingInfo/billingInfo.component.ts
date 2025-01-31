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
import { RegistrarService } from '../registrar/registrar.service';
import { MatSnackBar } from '@angular/material/snack-bar';

@Component({
  selector: 'app-billingInfo',
  templateUrl: './billingInfo.component.html',
  styleUrls: ['./billingInfo.component.scss'],
  standalone: false,
})
export class BillingInfoComponent {
  public static PATH = 'billingInfo';
  constructor(
    public registrarService: RegistrarService,
    private _snackBar: MatSnackBar
  ) {}

  driveFolderUrl = computed<string>(() => {
    if (this.registrarService.registrar()?.driveFolderId) {
      return (
        'https://drive.google.com/drive/folders/' +
        this.registrarService.registrar()?.driveFolderId
      );
    }
    return '';
  });

  openBillingDetails(e: MouseEvent) {
    if (!this.registrarService.registrar()?.driveFolderId) {
      e.preventDefault();
      this._snackBar.open('Billing Folder ID has not been assigned');
    }
  }
}
