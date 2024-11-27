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
  effect,
  EventEmitter,
  input,
  Input,
  Output,
  ViewChild,
} from '@angular/core';
import { MaterialModule } from '../material.module';
import { User, roleToDescription } from './users.service';
import { MatTableDataSource } from '@angular/material/table';
import { MatSort } from '@angular/material/sort';

export const columns = [
  {
    columnDef: 'emailAddress',
    header: 'User email',
    cell: (record: User) => `${record.emailAddress || ''}`,
  },
  {
    columnDef: 'role',
    header: 'User role',
    cell: (record: User) => `${roleToDescription(record.role)}`,
  },
];

@Component({
  selector: 'app-users-list',
  templateUrl: './usersList.component.html',
  styleUrls: ['./usersList.component.scss'],
  standalone: true,
  imports: [MaterialModule, CommonModule],
  providers: [],
})
export class UsersListComponent {
  columns = columns;
  displayedColumns = this.columns.map((c) => c.columnDef);
  dataSource: MatTableDataSource<User>;
  selectedRow!: User;
  users = input<User[]>([]);
  @Output() onSelect = new EventEmitter<User>();
  @ViewChild(MatSort) sort!: MatSort;

  constructor() {
    this.dataSource = new MatTableDataSource<User>(this.users());
    effect(() => {
      this.dataSource.data = this.users();
    });
  }

  ngAfterViewInit() {
    this.dataSource.sort = this.sort;
  }

  onClick(row: User) {
    this.selectedRow = row;
    this.onSelect.emit(row);
  }

  isRowSelected(row: User) {
    return row === this.selectedRow;
  }
}
