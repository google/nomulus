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
import { RegistrarService } from 'src/app/registrar/registrar.service';
import {
  GlobalLoader,
  GlobalLoaderService,
} from 'src/app/shared/services/globalLoader.service';
import { UserDataService } from 'src/app/shared/services/userData.service';
import { RdapService } from './rdap.service';

@Component({
  selector: 'app-rdap-edit',
  imports: [FormsModule, MaterialModule],
  templateUrl: './rdapEdit.component.html',
  styleUrls: ['./rdapEdit.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RdapEditComponent implements GlobalLoader {
  registrarInEdit = linkedSignal(() =>
    structuredClone(this.registrarService.registrar())
  );
  isUpdating = signal(false);

  constructor(
    public userDataService: UserDataService,
    public rdapService: RdapService,
    public registrarService: RegistrarService,
    private _snackBar: MatSnackBar,
    protected globalLoader: GlobalLoaderService
  ) {}

  loadingTimeout() {
    this._snackBar.open('Timeout updating RDAP settings');
  }

  save(e: SubmitEvent) {
    e.preventDefault();
    const registrar = this.registrarInEdit();
    if (!registrar) return;

    this.isUpdating.set(true);
    this.globalLoader.startGlobalLoader(this);
    this.rdapService.saveChanges(registrar).subscribe({
      complete: () => {
        this.isUpdating.set(false);
        this.globalLoader.stopGlobalLoader(this);
        this.rdapService.editing.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error || err.message);
        this.isUpdating.set(false);
        this.globalLoader.stopGlobalLoader(this);
      },
    });
  }
}
