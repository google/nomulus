import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, catchError, throwError, of } from 'rxjs';

@Injectable()
export class BackendService {
  constructor(private http: HttpClient) {}

  errorCatcher<Type>(
    error: HttpErrorResponse,
    mockData?: Type
  ): Observable<Type> {
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

    //   return throwError(() => {throw "Failed"});
    return of(<Type>mockData);
  }

  getContacts<Type>(registrarId: String): Observable<Type> {
    const mockData = [
      {
        name: 'Name Lastname',
        emailAddress: 'test@google.com',
        registrarId: 'zoomco',
        types: ['ADMIN'],
        visibleInWhoisAsAdmin: false,
        visibleInWhoisAsTech: false,
        visibleInDomainWhoisAsAbuse: false,
      },
      {
        name: 'Testname testlastname',
        emailAddress: 'testasd@google.com',
        registrarId: 'zoomco',
        visibleInWhoisAsAdmin: false,
        visibleInWhoisAsTech: false,
        visibleInDomainWhoisAsAbuse: false,
        types: ['BILLING'],
      },
    ];
    return this.http
      .get<Type>(`/console-api/settings/contacts/?registrarId=${registrarId}`)
      .pipe(catchError((err) => this.errorCatcher<Type>(err, <Type>mockData)));
  }

  postContacts<Type>(registrarId: String, contacts: Type): Observable<Type> {
    return this.http.post<Type>(
      `/console-api/settings/contacts/?registrarId=${registrarId}`,
      { contacts }
    );
  }
}
