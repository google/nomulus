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

import { CommonModule } from '@angular/common';
import {
  Component,
  ElementRef,
  EventEmitter,
  Inject,
  input,
  Output,
  ViewChild,
} from '@angular/core';
import { MaterialModule } from '../material.module';
import { FormsModule } from '@angular/forms';
import { User, UsersService } from './users.service';
import { UserDataService } from '../shared/services/userData.service';
import { BackendService } from '../shared/services/backend.service';
import { RegistrarService } from '../registrar/registrar.service';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogRef,
} from '@angular/material/dialog';
import { filter, switchMap, take } from 'rxjs';
import { MatSnackBar } from '@angular/material/snack-bar';
import { HttpErrorResponse } from '@angular/common/http';

@Component({
  selector: 'app-reset-lock-password-dialog',
  template: `
    <h2 mat-dialog-title>Please confirm the password reset:</h2>
    <mat-dialog-content>
      This will send a registry lock password reset email to
      {{ data.registryLockEmailAddress }}.
    </mat-dialog-content>
    <mat-dialog-actions>
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-button color="warn" (click)="onSave()">Confirm</button>
    </mat-dialog-actions>
  `,
  imports: [CommonModule, MaterialModule],
})
export class ResetRegistryLockPasswordComponent {
  constructor(
    public dialogRef: MatDialogRef<ResetRegistryLockPasswordComponent>,
    @Inject(MAT_DIALOG_DATA)
    public data: { registryLockEmailAddress: string }
  ) {}

  onSave(): void {
    this.dialogRef.close(true);
  }

  onCancel(): void {
    this.dialogRef.close(false);
  }
}

@Component({
  selector: 'app-user-edit-form',
  templateUrl: './userEditForm.component.html',
  styleUrls: ['./userEditForm.component.scss'],
  imports: [FormsModule, MaterialModule, CommonModule],
  providers: [],
})
export class UserEditFormComponent {
  @ViewChild('form') form!: ElementRef;
  isNew = input<boolean>(false);
  user = input<User, User>(
    {
      emailAddress: '',
      role: 'ACCOUNT_MANAGER',
      registryLockEmailAddress: '',
    },
    { transform: (user: User) => structuredClone(user) }
  );

  @Output() onEditComplete = new EventEmitter<User>();

  constructor(
    protected userDataService: UserDataService,
    private backendService: BackendService,
    private resetRegistryLockPasswordDialog: MatDialog,
    private registrarService: RegistrarService,
    private usersService: UsersService,
    private _snackBar: MatSnackBar
  ) {}

  saveEdit(e: SubmitEvent) {
    e.preventDefault();
    if (this.form.nativeElement.checkValidity()) {
      this.onEditComplete.emit(this.user());
    } else {
      this.form.nativeElement.reportValidity();
    }
  }

  sendRegistryLockPasswordResetRequest() {
    return this.backendService.requestRegistryLockPasswordReset(
      this.registrarService.registrarId(),
      this.user().registryLockEmailAddress!
    );
  }

  requestRegistryLockPasswordReset() {
    const dialogRef = this.resetRegistryLockPasswordDialog.open(
      ResetRegistryLockPasswordComponent,
      {
        data: {
          registryLockEmailAddress: this.user().registryLockEmailAddress,
        },
      }
    );
    dialogRef
      .afterClosed()
      .pipe(
        take(1),
        filter((result) => !!result)
      )
      .pipe(switchMap((_) => this.sendRegistryLockPasswordResetRequest()))
      .subscribe({
        next: (_) => this.usersService.currentlyOpenUserEmail.set(''),
        error: (err: HttpErrorResponse) =>
          this._snackBar.open(err.error || err.message),
      });
  }
}
