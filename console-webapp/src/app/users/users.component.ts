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
  effect,
  signal,
  untracked,
  ChangeDetectionStrategy,
} from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MaterialModule } from '../material.module';
import { RegistrarService } from '../registrar/registrar.service';
import { SelectedRegistrarWrapper } from '../shared/components/selectedRegistrarWrapper/selectedRegistrarWrapper.component';
import { UserDetailsComponent } from './userDetails.component';
import { User, UsersService } from './users.service';
import { FormsModule } from '@angular/forms';
import { UsersListComponent } from './usersList.component';
import { MatSelectChange } from '@angular/material/select';
import { UserEditFormComponent } from './userEditForm.component';

@Component({
  selector: 'app-users',
  templateUrl: './users.component.html',
  styleUrls: ['./users.component.scss'],
  imports: [
    FormsModule,
    MaterialModule,
    SelectedRegistrarWrapper,
    UsersListComponent,
    UserEditFormComponent,
    UserDetailsComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [UsersService],
})
export class UsersComponent {
  isLoading = signal(false);
  isNew = false;
  selectingExistingUser = signal(false);
  selectedRegistrarId = '';
  usersSelection = signal<User[]>([]);
  selectedExistingUser: User | undefined;

  constructor(
    protected registrarService: RegistrarService,
    protected usersService: UsersService,
    private _snackBar: MatSnackBar
  ) {
    effect(() => {
      if (registrarService.registrarId()) {
        untracked(() => {
          this.loadUsers();
        });
      }
    });
  }

  addExistingUser() {
    this.selectingExistingUser.set(true);
    this.selectedRegistrarId = '';
    this.usersSelection.set([]);
    this.selectedExistingUser = undefined;
  }

  existingUserSelected(user: User) {
    this.selectedExistingUser = user;
  }

  loadUsers() {
    this.isLoading.set(true);
    this.usersService.fetchUsers().subscribe({
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error || err.message);
        this.isLoading.set(false);
      },
      complete: () => {
        this.isLoading.set(false);
      },
    });
  }

  createNewUser(user: User) {
    this.isLoading.set(true);
    this.usersService.createOrAddNewUser(user).subscribe({
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error || err.message);
        this.isLoading.set(false);
      },
      complete: () => {
        this.isLoading.set(false);
      },
    });
  }

  openDetails(user: User) {
    this.usersService.currentlyOpenUserEmail.set(user.emailAddress);
  }

  onRegistrarSelectionChange(e: MatSelectChange) {
    if (e.value) {
      this.usersService.fetchUsersForRegistrar(e.value).subscribe({
        error: (err) => {
          this._snackBar.open(err.error || err.message);
        },
        next: (users) => {
          this.usersSelection.set(users);
        },
      });
    }
  }

  submitExistingUser() {
    this.isLoading.set(true);
    if (this.selectedExistingUser) {
      this.usersService
        .createOrAddNewUser(this.selectedExistingUser)
        .subscribe({
          error: (err) => {
            this._snackBar.open(err.error || err.message);
            this.isLoading.set(false);
          },
          complete: () => {
            this.isLoading.set(false);
            this.selectingExistingUser.set(false);
            this.loadUsers();
          },
        });
    }
  }
}
