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
import { Component } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import { SecurityService } from './security.service';
import { UserDataService } from 'src/app/shared/services/userData.service';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';

import { MaterialModule } from 'src/app/material.module';
import { filter, switchMap, take } from 'rxjs';
import { BackendService } from 'src/app/shared/services/backend.service';
import {
  PasswordInputForm,
  PasswordResults,
} from 'src/app/shared/components/passwordReset/passwordInputForm.component';

@Component({
  selector: 'app-reset-epp-password-dialog',
  template: `
    <h2 mat-dialog-title>Please confirm the password reset:</h2>
    <mat-dialog-content>
      This will send an EPP password reset email to the admin POC.
    </mat-dialog-content>
    <mat-dialog-actions>
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-button color="warn" (click)="onSave()">Confirm</button>
    </mat-dialog-actions>
  `,
  imports: [MaterialModule],
})
export class ResetEppPasswordComponent {
  constructor(public dialogRef: MatDialogRef<ResetEppPasswordComponent>) {}

  onSave(): void {
    this.dialogRef.close(true);
  }

  onCancel(): void {
    this.dialogRef.close(false);
  }
}

@Component({
  selector: 'app-epp-password-edit',
  templateUrl: './eppPasswordEdit.component.html',
  styleUrls: ['./eppPasswordEdit.component.scss'],
  standalone: false,
})
export default class EppPasswordEditComponent {
  static EPP_VALIDATORS = [
    Validators.required,
    Validators.minLength(6),
    Validators.maxLength(16),
    PasswordInputForm.newPasswordsMatch,
  ];

  passwordUpdateForm = new FormGroup({
    oldPassword: new FormControl('', [Validators.required]),
    newPassword: new FormControl('', EppPasswordEditComponent.EPP_VALIDATORS),
    newPasswordRepeat: new FormControl(
      '',
      EppPasswordEditComponent.EPP_VALIDATORS
    ),
  });

  constructor(
    public registrarService: RegistrarService,
    public securityService: SecurityService,
    protected userDataService: UserDataService,
    private backendService: BackendService,
    private resetPasswordDialog: MatDialog,
    private _snackBar: MatSnackBar
  ) {}

  save(passwordResults: PasswordResults) {
    this.securityService
      .saveEppPassword({
        registrarId: this.registrarService.registrarId(),
        oldPassword: passwordResults.oldPassword!,
        newPassword: passwordResults.newPassword,
        newPasswordRepeat: passwordResults.newPasswordRepeat,
      })
      .subscribe({
        complete: () => {
          this.goBack();
        },
        error: (err: HttpErrorResponse) => {
          this._snackBar.open(err.error);
        },
      });
  }

  goBack() {
    this.securityService.isEditingPassword = false;
  }

  sendEppPasswordResetRequest() {
    return this.backendService.requestEppPasswordReset(
      this.registrarService.registrarId()
    );
  }

  requestEppPasswordReset() {
    const dialogRef = this.resetPasswordDialog.open(ResetEppPasswordComponent);
    dialogRef
      .afterClosed()
      .pipe(
        take(1),
        filter((result) => !!result)
      )
      .pipe(switchMap((_) => this.sendEppPasswordResetRequest()))
      .subscribe({
        next: (_) => this.goBack(),
        error: (err: HttpErrorResponse) =>
          this._snackBar.open(err.error || err.message),
      });
  }
}
