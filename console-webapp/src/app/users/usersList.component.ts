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

import {
  Component,
  effect,
  input,
  output,
  signal,
  viewChild,
  ChangeDetectionStrategy,
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
  imports: [MaterialModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [],
})
export class UsersListComponent {
  columns = columns;
  displayedColumns = this.columns.map((c) => c.columnDef);
  dataSource: MatTableDataSource<User>;
  readonly selectedRow = signal<User | undefined>(undefined);
  users = input<User[]>([]);
  onSelect = output<User>();
  sort = viewChild(MatSort);

  constructor() {
    this.dataSource = new MatTableDataSource<User>(this.users());
    const columnByDef = new Map(this.columns.map((c) => [c.columnDef, c]));
    this.dataSource.sortingDataAccessor = (
      item: User,
      property: string
    ): string | number =>
      (columnByDef.get(property)?.cell(item) ?? '').toLowerCase();
    effect(() => {
      const currentUsers = this.users();
      this.dataSource.data = currentUsers;
      this.dataSource.sort = this.sort() ?? null;
      const selected = this.selectedRow();
      if (selected && !currentUsers.includes(selected)) {
        this.selectedRow.set(undefined);
      }
    });
  }

  onClick(row: User) {
    this.selectedRow.set(row);
    this.onSelect.emit(row);
  }

  isRowSelected(row: User) {
    return row === this.selectedRow();
  }
}
