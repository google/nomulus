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

import { HttpErrorResponse } from '@angular/common/http';
import { Component } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
  Registrar,
  RegistrarService,
} from 'src/app/registrar/registrar.service';

import { WhoisService } from './whois.service';

@Component({
  selector: 'app-whois',
  templateUrl: './whois.component.html',
  styleUrls: ['./whois.component.scss'],
  providers: [WhoisService],
})
export default class WhoisComponent {
  public static PATH = 'whois';
  loading = false;
  inEdit = false;
  registrar: Registrar;

  constructor(
    public whoisService: WhoisService,
    public registrarService: RegistrarService,
    private _snackBar: MatSnackBar
  ) {
    this.registrar = JSON.parse(
      JSON.stringify(this.registrarService.registrar)
    );
  }

  enableEdit() {
    this.inEdit = true;
  }

  cancel() {
    this.inEdit = false;
    this.resetDataSource();
  }

  save() {
    this.loading = true;
    this.whoisService.saveChanges(this.registrar).subscribe({
      complete: () => {
        this.loading = false;
        this.resetDataSource();
      },
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error, undefined, {
          duration: 1500,
        });
      },
    });
    this.cancel();
  }

  resetDataSource() {
    this.registrar = JSON.parse(
      JSON.stringify(this.registrarService.registrar)
    );
  }
}
