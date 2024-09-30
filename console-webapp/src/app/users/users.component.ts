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
import { Component, effect, ViewChild } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatSort } from '@angular/material/sort';
import { MatTableDataSource } from '@angular/material/table';
import { SelectedRegistrarModule } from '../app.module';
import { MaterialModule } from '../material.module';
import { RegistrarService } from '../registrar/registrar.service';
import { SnackBarModule } from '../snackbar.module';
import { User, UsersService } from './users.service';

export const columns = [
  {
    columnDef: 'email_address',
    header: 'User email',
    cell: (record: User) => `${record.email_address || ''}`,
  },
  {
    columnDef: 'role',
    header: 'User role',
    cell: (record: User) => `${record.role || ''}`,
  },
];

@Component({
  selector: 'app-users',
  templateUrl: './users.component.html',
  styleUrls: ['./users.component.scss'],
  standalone: true,
  imports: [
    MaterialModule,
    SnackBarModule,
    CommonModule,
    SelectedRegistrarModule,
  ],
  providers: [UsersService],
})
export class UsersComponent {
  dataSource: MatTableDataSource<User>;
  columns = columns;
  displayedColumns = this.columns.map((c) => c.columnDef);

  @ViewChild(MatSort) sort!: MatSort;

  constructor(
    protected registrarService: RegistrarService,
    protected usersService: UsersService,
    private _snackBar: MatSnackBar
  ) {
    this.dataSource = new MatTableDataSource<User>(usersService.users());

    effect(() => {
      if (registrarService.registrarId()) {
        this.loadUsers();
      }
    });
    effect(() => {
      this.dataSource.data = usersService.users();
    });
  }

  ngAfterViewInit() {
    this.dataSource.sort = this.sort;
  }

  loadUsers() {
    this.usersService.fetchUsers().subscribe({
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error || err.message);
      },
    });
  }

  createNewUser() {
    this.usersService.createNewUser().subscribe({
      next: (newUser) => {
        this._snackBar.open(
          `New user with email ${newUser.email_address} has been created.`,
          '',
          {
            duration: 2000,
          }
        );
      },
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error || err.message);
      },
    });
  }
}
