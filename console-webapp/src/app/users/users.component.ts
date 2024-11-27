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
import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { SelectedRegistrarModule } from '../app.module';
import { MaterialModule } from '../material.module';
import { RegistrarService } from '../registrar/registrar.service';
import { SnackBarModule } from '../snackbar.module';
import { UserEditComponent } from './userEdit.component';
import { User, UsersService } from './users.service';
import { UserDataService } from '../shared/services/userData.service';
import { FormsModule } from '@angular/forms';
import { UsersListComponent } from './usersList.component';
import { MatSelectChange } from '@angular/material/select';

@Component({
  selector: 'app-users',
  templateUrl: './users.component.html',
  styleUrls: ['./users.component.scss'],
  standalone: true,
  imports: [
    FormsModule,
    MaterialModule,
    SnackBarModule,
    CommonModule,
    SelectedRegistrarModule,
    UsersListComponent,
    UserEditComponent,
  ],
  providers: [UsersService],
})
export class UsersComponent {
  isLoading = false;
  selectingExistingUser = false;
  selectedRegistrarId = '';
  usersSelection: User[] = [];
  selectedExistingUser: User | undefined;

  constructor(
    protected registrarService: RegistrarService,
    protected usersService: UsersService,
    private userDataService: UserDataService,
    private _snackBar: MatSnackBar
  ) {
    effect(() => {
      if (registrarService.registrarId()) {
        this.loadUsers();
      }
    });
  }

  addExistingUser() {
    this.selectingExistingUser = true;
    this.selectedRegistrarId = '';
    this.usersSelection = [];
    this.selectedExistingUser = undefined;
  }

  existingUserSelected(user: User) {
    this.selectedExistingUser = user;
  }

  loadUsers() {
    this.isLoading = true;
    this.usersService.fetchUsers().subscribe({
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error || err.message);
        this.isLoading = false;
      },
      complete: () => {
        this.isLoading = false;
      },
    });
  }

  createNewUser() {
    this.isLoading = true;
    this.usersService.createOrAddNewUser(null).subscribe({
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error || err.message);
        this.isLoading = false;
      },
      complete: () => {
        this.isLoading = false;
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
          this.usersSelection = users;
        },
      });
    }
  }

  submitExistingUser() {
    this.isLoading = true;
    if (this.selectedExistingUser) {
      this.usersService
        .createOrAddNewUser(this.selectedExistingUser)
        .subscribe({
          error: (err) => {
            this._snackBar.open(err.error || err.message);
            this.isLoading = false;
          },
          complete: () => {
            this.isLoading = false;
            this.selectingExistingUser = false;
            this.loadUsers();
          },
        });
    }
  }
}
