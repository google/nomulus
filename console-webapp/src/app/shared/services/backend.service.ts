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

import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, catchError, of, throwError } from 'rxjs';

import { DomainListResult } from 'src/app/domains/domainList.service';
import {
  Registrar,
  SecuritySettingsBackendModel,
  WhoisRegistrarFields,
} from '../../registrar/registrar.service';
import { Contact } from '../../settings/contact/contact.service';
import { UserData } from './userData.service';

@Injectable()
export class BackendService {
  constructor(private http: HttpClient) {}

  errorCatcher<Type>(
    error: HttpErrorResponse,
    mockData?: Type
  ): Observable<Type> {

    // This is a temporary redirect to the old console untill the new console
    // is fully released and enabled
    if(error.url && window.location.href.indexOf(error.url) < 0) {
      window.location.href = error.url;
    }
    if (error.error instanceof Error) {
      // A client-side or network error occurred. Handle it accordingly.
      console.error('An error occurred:', error.error.message);
    } else {
      // The backend returned an unsuccessful response code.
      // The response body may contain clues as to what went wrong,
      console.error(
        `Backend returned code ${error.status}, body was: ${error.error}`
      );
    }

    if (mockData) {
      return of(<Type>mockData);
    } else {
      return throwError(() => error);
    }
  }

  getContacts(registrarId: string): Observable<Contact[]> {
    return this.http
      .get<Contact[]>(
        `/console-api/settings/contacts?registrarId=${registrarId}`
      )
      .pipe(catchError((err) => this.errorCatcher<Contact[]>(err)));
  }

  postContacts(
    registrarId: string,
    contacts: Contact[]
  ): Observable<Contact[]> {
    return this.http.post<Contact[]>(
      `/console-api/settings/contacts?registrarId=${registrarId}`,
      contacts
    );
  }

  getDomains(
    registrarId: string,
    checkpointTime?: string,
    pageNumber?: number,
    resultsPerPage?: number,
    totalResults?: number,
    searchTerm?: string
  ): Observable<DomainListResult> {
    var url = `/console-api/domain-list?registrarId=${registrarId}`;
    if (checkpointTime) {
      url += `&checkpointTime=${checkpointTime}`;
    }
    if (pageNumber) {
      url += `&pageNumber=${pageNumber}`;
    }
    if (resultsPerPage) {
      url += `&resultsPerPage=${resultsPerPage}`;
    }
    if (totalResults) {
      url += `&totalResults=${totalResults}`;
    }
    if (searchTerm) {
      url += `&searchTerm=${searchTerm}`;
    }
    return this.http
      .get<DomainListResult>(url)
      .pipe(catchError((err) => this.errorCatcher<DomainListResult>(err)));
  }

  getRegistrars(): Observable<Registrar[]> {
    return this.http
      .get<Registrar[]>('/console-api/registrars')
      .pipe(catchError((err) => this.errorCatcher<Registrar[]>(err)));
  }

  postRegistrar(registrar: Registrar): Observable<Registrar> {
    return this.http
      .post<Registrar>('/console-api/registrar', registrar)
      .pipe(catchError((err) => this.errorCatcher<Registrar>(err)));
  }

  getSecuritySettings(
    registrarId: string
  ): Observable<SecuritySettingsBackendModel> {
    return this.http
      .get<SecuritySettingsBackendModel>(
        `/console-api/settings/security?registrarId=${registrarId}`
      )
      .pipe(
        catchError((err) =>
          this.errorCatcher<SecuritySettingsBackendModel>(err)
        )
      );
  }

  postSecuritySettings(
    registrarId: string,
    securitySettings: SecuritySettingsBackendModel
  ): Observable<SecuritySettingsBackendModel> {
    return this.http.post<SecuritySettingsBackendModel>(
      `/console-api/settings/security?registrarId=${registrarId}`,
      securitySettings
    );
  }

  getUserData(): Observable<UserData> {
    return this.http
      .get<UserData>('/console-api/userdata')
      .pipe(catchError((err) => this.errorCatcher<UserData>(err)));
  }

  postWhoisRegistrarFields(
    whoisRegistrarFields: WhoisRegistrarFields
  ): Observable<WhoisRegistrarFields> {
    return this.http.post<WhoisRegistrarFields>(
      '/console-api/settings/whois-fields',
      whoisRegistrarFields
    );
  }
}
