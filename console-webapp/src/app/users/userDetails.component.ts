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
  computed,
  DestroyRef,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MaterialModule } from '../material.module';
import { RegistrarService } from '../registrar/registrar.service';
import { UsersService, roleToDescription, User } from './users.service';
import { UserEditFormComponent } from './userEditForm.component';

@Component({
  selector: 'app-user-edit',
  templateUrl: './userDetails.component.html',
  styleUrls: ['./userDetails.component.scss'],
  imports: [MaterialModule, UserEditFormComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [],
})
export class UserDetailsComponent {
  private readonly destroyRef = inject(DestroyRef);
  readonly isEditing = signal(false);
  readonly isLoading = signal(false);
  readonly isPasswordVisible = signal(false);

  private readonly detailState = computed(() => {
    const state = this.usersService.viewState();
    return state.kind === 'detail' ? state : null;
  });

  readonly isNewlyCreated = computed(
    () => this.detailState()?.isNewlyCreated ?? false
  );

  readonly userDetails = computed<User | undefined>(() => {
    const detail = this.detailState();
    return detail
      ? this.usersService.users().find((u) => u.emailAddress === detail.email)
      : undefined;
  });

  constructor(
    protected registrarService: RegistrarService,
    protected usersService: UsersService,
    private _snackBar: MatSnackBar
  ) {}

  roleToDescription(role: string) {
    return roleToDescription(role);
  }

  togglePasswordVisibility() {
    this.isPasswordVisible.update((visible) => !visible);
  }

  deleteUser() {
    const user = this.userDetails();
    if (!user || this.isLoading()) {
      return;
    }
    if (confirm('This will permanently delete the user ' + user.emailAddress)) {
      this.isLoading.set(true);
      this.usersService
        .deleteUser(user)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: () => {
            this.isLoading.set(false);
            this.goBack();
          },
          error: (err: HttpErrorResponse) => {
            this._snackBar.open(err.error || err.message);
            this.isLoading.set(false);
          },
        });
    }
  }

  goBack() {
    this.usersService.returnToList();
  }

  saveEdit(user: User) {
    if (this.isLoading()) {
      return;
    }
    this.isLoading.set(true);
    this.usersService
      .updateUser(user)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.isLoading.set(false);
          this.isEditing.set(false);
          this.usersService.openUserDetails(user.emailAddress, false);
        },
        error: (err: HttpErrorResponse) => {
          this._snackBar.open(err.error || err.message);
          this.isLoading.set(false);
        },
      });
  }
}
