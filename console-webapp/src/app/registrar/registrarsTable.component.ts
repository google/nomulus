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

import { Component, effect, ViewChild, ViewEncapsulation } from '@angular/core';
import { MatPaginator } from '@angular/material/paginator';
import { MatSort } from '@angular/material/sort';
import { MatTableDataSource } from '@angular/material/table';
import { Router } from '@angular/router';
import { RESTRICTED_ELEMENTS } from '../shared/directives/userLevelVisiblity.directive';
import { Registrar, RegistrarService } from './registrar.service';
import { PATHS } from '../app-routing.module';
import { environment } from '../../environments/environment';

export const columns = [
  {
    columnDef: 'registrarId',
    header: 'Registrar Id',
    cell: (record: Registrar) => `${record.registrarId || ''}`,
    hiddenOnDetailsCard: true,
  },
  {
    columnDef: 'registrarName',
    header: 'Name',
    cell: (record: Registrar) => `${record.registrarName || ''}`,
    hiddenOnDetailsCard: true,
  },
  {
    columnDef: 'allowedTlds',
    header: 'TLDs',
    cell: (record: Registrar) => `${(record.allowedTlds || []).join(', ')}`,
  },
  {
    columnDef: 'emailAddress',
    header: 'Username',
    cell: (record: Registrar) => `${record.emailAddress || ''}`,
  },
  {
    columnDef: 'ianaIdentifier',
    header: 'IANA ID',
    cell: (record: Registrar) => `${record.ianaIdentifier || ''}`,
  },
  {
    columnDef: 'billingAccountMap',
    header: 'Billing Accounts',
    cell: (record: Registrar) =>
      `${Object.entries(record.billingAccountMap || {}).reduce(
        (acc, [key, val]) => {
          return `${acc}${key}=${val}<br/>`;
        },
        ''
      )}`,
  },
  {
    columnDef: 'registryLockAllowed',
    header: 'Registry Lock',
    cell: (record: Registrar) => `${record.registryLockAllowed}`,
  },
  {
    columnDef: 'driveId',
    header: 'Drive ID',
    cell: (record: Registrar) => `${record.driveFolderId || ''}`,
  },
];

@Component({
  selector: 'app-registrar',
  templateUrl: './registrarsTable.component.html',
  styleUrls: ['./registrarsTable.component.scss'],
  encapsulation: ViewEncapsulation.None,
})
export class RegistrarComponent {
  public static PATH = 'registrars';
  dataSource: MatTableDataSource<Registrar>;
  columns = columns;
  oteButtonVisible = environment.sandbox;
  displayedColumns = this.columns.map((c) => c.columnDef);

  @ViewChild(MatPaginator) paginator!: MatPaginator;
  @ViewChild(MatSort) sort!: MatSort;

  constructor(
    protected registrarService: RegistrarService,
    private router: Router
  ) {
    this.dataSource = new MatTableDataSource<Registrar>(
      registrarService.registrars()
    );
    effect(() => {
      this.dataSource.data = registrarService.registrars();
    });
  }

  ngAfterViewInit() {
    this.dataSource.paginator = this.paginator;
    this.dataSource.sort = this.sort;
  }

  createOteAccount() {
    this.router.navigate([PATHS.NewOteComponent]);
  }

  getElementIdForOteBlock() {
    return RESTRICTED_ELEMENTS.OTE;
  }

  openDetails(registrarId: string) {
    this.router.navigate(['registrars/', registrarId], {
      queryParamsHandling: 'merge',
    });
  }

  applyFilter(event: Event) {
    const filterValue = (event.target as HTMLInputElement).value;
    // TODO: consider filteing out only by registrar name
    this.dataSource.filter = filterValue.trim().toLowerCase();
  }

  openNewRegistrar() {
    this.registrarService.inNewRegistrarMode.set(true);
  }
}
