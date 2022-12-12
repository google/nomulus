// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

import { Component, OnInit } from '@angular/core';

export interface ActivityRecord {
  eventType: string;
  userName: string;
  registrarName: string;
  timestamp: string;
  details: string
}

const MOCK_DATA: ActivityRecord[] = [
  {eventType: "Export DUMS", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Update Contact", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Delete Domain", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Export DUMS", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Update Contact", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Delete Domain", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Export DUMS", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Update Contact", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Delete Domain", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Export DUMS", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Update Contact", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Delete Domain", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Export DUMS", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Update Contact", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Delete Domain", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Export DUMS", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Update Contact", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Delete Domain", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Export DUMS", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Update Contact", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Delete Domain", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Export DUMS", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Update Contact", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
  {eventType: "Delete Domain", userName:"user3", registrarName: "registrar1", timestamp: "2022-03-15T19:46:39.007", details: "All Domains under management exported as .csv file" },
];


@Component({
  selector: 'app-home',
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.less']
})
export class HomeComponent {

  columns = [
    {
      columnDef: 'eventType',
      header: 'Event Type',
      cell:(record: ActivityRecord) => `${record.eventType}`,
    },
    {
      columnDef: 'userName',
      header: 'User',
      cell: (record: ActivityRecord)  => `${record.userName}`,
    },
    {
      columnDef: 'registrarName',
      header: 'Registrar',
      cell: (record: ActivityRecord)  => `${record.registrarName}`,
    },
    {
      columnDef: 'timestamp',
      header: 'Timestamp',
      cell: (record: ActivityRecord)  => `${record.timestamp}`,
    },
    {
      columnDef: 'details',
      header: 'Details',
      cell: (record: ActivityRecord)  => `${record.details}`,
    },
  ];
  dataSource = MOCK_DATA;
  displayedColumns = this.columns.map(c => c.columnDef);

  constructor() {


  }
}
