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

import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit } from '@angular/core';
import { MatChipInputEvent } from '@angular/material/chips';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, ParamMap, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { RESTRICTED_ELEMENTS } from '../shared/directives/userLevelVisiblity.directive';
import { Registrar, RegistrarService } from './registrar.service';
import { RegistrarComponent, columns } from './registrarsTable.component';
import { environment } from '../../environments/environment';

@Component({
  selector: 'app-registrar-details',
  templateUrl: './registrarDetails.component.html',
  styleUrls: ['./registrarDetails.component.scss'],
})
export class RegistrarDetailsComponent implements OnInit {
  public static PATH = 'registrars/:id';
  inEdit: boolean = false;
  oteButtonVisible = environment.sandbox;
  registrarInEdit!: Registrar;
  registrarNotFound: boolean = true;
  columns = columns.filter((c) => !c.hiddenOnDetailsCard);
  private subscription!: Subscription;

  constructor(
    protected registrarService: RegistrarService,
    private route: ActivatedRoute,
    private _snackBar: MatSnackBar,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.registrarService.registrarsLoaded.then(() => {
      this.subscription = this.route.paramMap.subscribe((params: ParamMap) => {
        this.registrarInEdit = structuredClone(
          this.registrarService
            .registrars()
            .filter((r) => r.registrarId === params.get('id'))[0]
        );
        if (!this.registrarInEdit) {
          this._snackBar.open(
            `Registrar with id ${params.get('id')} is not available`
          );
          this.registrarNotFound = true;
        } else {
          this.registrarNotFound = false;
        }
      });
    });
  }

  addTLD(e: MatChipInputEvent) {
    this.registrarInEdit.allowedTlds = this.registrarInEdit.allowedTlds || [];
    this.removeTLD(e.value); // Prevent dups
    this.registrarInEdit.allowedTlds = [
      ...this.registrarInEdit.allowedTlds,
      e.value.toLowerCase(),
    ];
  }

  checkOteStatus() {
    this.router.navigate(['ote-status/', this.registrarInEdit.registrarId], {
      queryParamsHandling: 'merge',
    });
  }

  getElementIdForOteBlock() {
    return RESTRICTED_ELEMENTS.OTE;
  }

  removeTLD(tld: string) {
    this.registrarInEdit.allowedTlds = this.registrarInEdit.allowedTlds?.filter(
      (v) => v != tld
    );
  }

  saveAndClose() {
    this.registrarService.updateRegistrar(this.registrarInEdit).subscribe({
      complete: () => {
        this.router.navigate([RegistrarComponent.PATH], {
          queryParamsHandling: 'merge',
        });
      },
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error);
      },
    });
    this.inEdit = false;
  }

  ngOnDestroy() {
    this.subscription && this.subscription.unsubscribe();
  }
}
