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
import { Component, effect } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
  Registrar,
  RegistrarService,
} from 'src/app/registrar/registrar.service';
import { UserDataService } from 'src/app/shared/services/userData.service';
import { RdapService } from './rdap.service';

@Component({
  selector: 'app-rdap-edit',
  templateUrl: './rdapEdit.component.html',
  styleUrls: ['./rdapEdit.component.scss'],
  standalone: false,
})
export default class RdapEditComponent {
  registrarInEdit: Registrar | undefined;

  constructor(
    public userDataService: UserDataService,
    public rdapService: RdapService,
    public registrarService: RegistrarService,
    private _snackBar: MatSnackBar
  ) {
    effect(() => {
      const registrar = this.registrarService.registrar();
      if (registrar) {
        this.registrarInEdit = structuredClone(registrar);
      }
    });
  }

  save(e: SubmitEvent) {
    e.preventDefault();
    if (!this.registrarInEdit) return;

    this.rdapService.saveChanges(this.registrarInEdit).subscribe({
      complete: () => {
        this.rdapService.editing = false;
      },
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error);
      },
    });
  }
}
